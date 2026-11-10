"""Runs the app's regular-shot rules on a SCREEN RECORDING (no phone, no app session needed).

shotsim.py [-s sens] [--up] [--crouch] [-q] <recording.mp4 | folder> ...

A port of ShotWatcher.kt (vc1070), function by function with the same names: readSide, findDisc, readSlot2, findTop,
findCentre, armsUp, onDisc, armsUpEvent, crouch, checkShot (fresh / v2 gap / early), checkPending, checkUpPending,
findMe, and the box-out step of sweep() as the release clock. Left out: skillDark (tools/analysis/darksim.py),
findColRing (logged only), the pre-arm, clips. Input: `adb screenrecord` at 1560x720 (~118 fps); every 2nd frame is
scanned, like the app. Writes next to the video <name>.sim.csv (per scan, shot.csv column names) and <name>.simev.csv
(events, video seconds; cached, delete them to scan again), then prints one line per reference moment with what the
rules did from 1.6 s before to 0.35 s after it, in ms relative to that moment. Reference moments: "GAME PRESS" = the
game's own press shown on the block button in a demonstration ("시연 중"; our PRESS should read near 0), "block flash"
(the game's press is 250-300 ms before it, HANDOFF 0-4h/0-4i), "dark onset", "release step" (ball leaves the hand).
Check of the port against the app's own log of the same minutes: shotsim.py --check <game folder> <mp4> <offset s>
(offset = session time - video time).
"""
import sys, os, csv, glob, math, bisect
import cv2, numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vidskill

FX, FY = 0.8733, 0.8418
BASE = 10 ** 12                      # the app's clock is nanoTime (huge); 0 means "never" in the rules
UNK, DEF, OFF = 0, 1, 2
SIDE = {UNK: "unk", DEF: "def", OFF: "off"}
FOUND_FILL = 0.45; FAST_SPEED = 0.9; CR_N = 96; CROUCH_SINK = 0.0097; CROUCH_WIN_NS = 170_000_000; CROUCH_CALM = 0.0125
CROUCH_DELAYS = [80, 120, 160]; UP_DY = 0.0195; UP_HOLD_NS = 30_000_000
GAP_RISE = [0.04, 0.03, 0.02]; EARLY_RISE = [0.06, 0.05, 0.04]; FRESH_RISE = [0.05, 0.04, 0.04]; EARLY_DELAY_MS = 25
CONFIRM_XSPAN = 0.1; CONFIRM_RISE = 0.025; MOVE_RISE = 0.06; CONFIRM_WINDOW_NS = 200_000_000; CALM_XSPAN = 0.03; ME_FAR = 0.14
HN = 64
F32 = np.float32


def disc_red(px):
    """px: (..., 3) BGR uint8 -> bool, the marker / foot-ring red of ShotWatcher."""
    b = px[..., 0].astype(np.int16); g = px[..., 1].astype(np.int16); r = px[..., 2].astype(np.int16)
    return (r > 160) & (g < 25) & (b >= 30) & (b <= 90) & (r - b > 95)


class Sim:
    def __init__(self, sens=1, up_press=False, crouch_press=False, shot_delay=0, fx=FX, fy=FY):
        self.sens = sens; self.upPress = up_press; self.crouchPress = crouch_press; self.shotDelay = shot_delay
        self.fx = fx; self.fy = fy
        self.key = None
        self.hT = [0] * HN; self.hX = [0.0] * HN; self.hY = [0.0] * HN; self.hPos = 0; self.hCount = 0
        self.lastFastNs = 0
        self.gT = [0] * HN; self.gV = [0.0] * HN; self.gPos = 0; self.gCount = 0
        self.gapNs = 0; self.gapRing = -1.0; self.gJumpNs = 0
        self.runNs = 0; self.runY0 = 0.0; self.runXMin = 0.0; self.runXMax = 0.0
        self.runLastNs = 0; self.runLastX = 0.0; self.runLastY = 0.0
        self.runG = 0; self.runG0 = 0.0; self.runG1 = 0.0; self.runRMin = 0.0; self.runRMax = 0.0
        self.lastShotFireNs = 0; self.lastDefNs = 0; self.lastOffNs = 0
        self.pendNs = 0; self.pendY = 0.0; self.pendDetail = ""; self.pendRise = CONFIRM_RISE; self.pendDrop = "rejected_norise"
        self.cT = [0] * CR_N; self.cY = [0.0] * CR_N; self.cNn = [0] * CR_N; self.cPos = 0; self.cCount = 0; self.cRunNs = 0
        self.lastCrouchNs = 0; self.crouchSeq = 0
        self.lastStandNs = 0; self.upPendNs = 0; self.lastPressNs = 0
        self.upRun = 0; self.upSinceNs = 0; self.lastLowNs = 0; self.lastUpEventNs = 0
        self.secX = -1.0; self.secY = -1.0; self.ringY = -1.0; self.ringX = -1.0; self.ringYLast = -1.0; self.lastRingNs = 0
        self.discX = -1.0; self.discY = -1.0; self.fill = 0.0; self.second = 0.0
        self.topDy = -1.0; self.ycY = -1.0; self.ycN = 0; self.markBot = -1
        self.sideDark = 0.0; self.sideOrange = 0.0; self.slot2Luma = -1.0
        self.meX = -1.0; self.meY = -1.0
        self.side = UNK
        self.swL = [0.0] * 16; self.swN = 0; self.swRelNs = 0
        self.events = []; self.rows = []

    # ---------------------------------------------------------------- frame
    def ensureGrid(self, w, h):
        if self.key == (w, h): return
        self.key = (w, h); self.w = w; self.h = h
        self.aspect = w / h
        self.step = s = max(2, w // 390)
        self.gw = w // s; self.gh = h // s
        self.k = max(3, int(math.floor(0.0104 * w / s + 0.5)))
        px = np.arange(self.gw) * s; py = np.arange(self.gh) * s
        X, Y = np.meshgrid(px, py)
        self.hud = ((X < 0.30 * w) & (Y < 0.22 * h)) | ((X > 0.62 * w) & (Y < 0.14 * h)) | ((X > 0.74 * w) & (Y > 0.40 * h)) | ((X < 0.32 * w) & (Y > 0.50 * h))
        act = np.zeros(self.gh, bool); act[int(0.06 * self.gh):int(0.88 * self.gh)] = True
        self.scanmask = act[:, None] & ~self.hud
        # readSide sample points (a disc), readSlot2 sample points (a square)
        cx = int(self.fx * w); cy = int(self.fy * h); r = int(0.045 * w); st = max(2, int(0.0028 * w))
        ys, xs = np.meshgrid(np.arange(cy - r, cy + r + 1, st), np.arange(cx - r, cx + r + 1, st), indexing="ij")
        ok = (ys >= 0) & (ys < h) & (xs >= 0) & (xs < w) & ((xs - cx) ** 2 + (ys - cy) ** 2 <= r * r)
        self.sideY = ys[ok]; self.sideX = xs[ok]
        cx = int(F32(self.fx - 0.09) * w); cy = int(F32(self.fy - 0.21) * h); r = int(0.03 * w)
        ys, xs = np.meshgrid(np.arange(cy - r, cy + r + 1, st), np.arange(cx - r, cx + r + 1, st), indexing="ij")
        ok = (ys >= 0) & (ys < h) & (xs >= 0) & (xs < w)
        self.s2Y = ys[ok]; self.s2X = xs[ok]
        self.hCount = 0

    def onFrame(self, im, tNs):
        h, w = im.shape[:2]
        self.ensureGrid(w, h)
        self.im = im
        self.side = self.readSide(im)
        found = self.findDisc(im, tNs)
        self.readSlot2(im)
        if self.side == DEF: self.lastDefNs = tNs
        elif self.side == OFF: self.lastOffNs = tNs
        self.sweep(tNs)
        self.topDy = -1.0; self.ycY = -1.0; self.ycN = 0
        if found:
            self.findTop(im); self.findCentre(im)
            self.armsUp(tNs)
            self.onDisc(tNs)
            self.armsUpEvent(tNs)
            self.crouch(tNs)
        self.checkPending(tNs, found)
        self.checkUpPending(tNs, found)
        self.rows.append(((tNs - BASE) / 1e9, SIDE[self.side], 1 if found else 0, self.discX, self.discY, self.fill, self.second,
                          self.sideDark, self.sideOrange, self.slot2Luma, self.ringY, self.topDy, self.ycY, self.ycN))

    def event(self, tNs, name, detail):
        self.events.append(((tNs - BASE) / 1e9, name, SIDE[self.side], self.discX, self.discY, detail))

    # ---------------------------------------------------------------- side, slot2, release step
    def readSide(self, im):
        p = im[self.sideY, self.sideX].astype(np.int16)
        b, g, r = p[:, 0], p[:, 1], p[:, 2]
        n = len(p)
        if n == 0: return UNK
        df = int(((r < 70) & (g < 70) & (b < 90)).sum()) / n
        of = int(((r > 190) & (g >= 80) & (g <= 190) & (b < 90)).sum()) / n
        self.sideDark = df; self.sideOrange = of
        if df > 0.15 and of < 0.005: return DEF
        if of > 0.01: return OFF
        return UNK

    def readSlot2(self, im):
        p = im[self.s2Y, self.s2X].astype(np.int32)
        l = (p[:, 2] * 77 + p[:, 1] * 151 + p[:, 0] * 28) >> 8
        self.slot2Luma = float(l.sum()) / len(l) if len(l) else -1.0

    def sweep(self, tNs):
        """Only the box-out step of ShotWatcher.sweep: the release clock of the practice court ("sweep_rel")."""
        l = self.slot2Luma
        self.swL = self.swL[1:] + [l]
        self.swN += 1
        if self.swN >= 16:
            L = self.swL
            mn = min(L[0:13]); mx = max(L[0:13]); b = L[12]; d = l - b
            if mx - mn < 1.5 and abs(L[14] - b) < 5 and 6 <= d <= 12 and 98 <= b <= 112 and tNs - self.swRelNs > 300_000_000:
                self.swRelNs = tNs
                self.event(tNs, "sweep_rel", "step %.1f->%.1f" % (b, l))

    # ---------------------------------------------------------------- disc
    def findDisc(self, im, tNs):
        s = self.step; gw = self.gw; gh = self.gh; k = self.k; w = self.w; h = self.h
        sub = im[0:gh * s:s, 0:gw * s:s]
        self.sub = sub
        red = disc_red(sub) & self.scanmask
        sat = np.zeros((gh + 1, gw + 1), np.int32)
        sat[1:, 1:] = red.cumsum(0).cumsum(1)
        C = sat[k:, k:] - sat[:-k, k:] - sat[k:, :-k] + sat[:-k, :-k]
        i = int(C.argmax()); by, bx = divmod(i, C.shape[1]); best = int(C[by, bx])
        if best == 0: bx = by = 0
        far = 2 * k
        C2 = C.copy(); C2[max(0, by - far):by + far + 1, max(0, bx - far):bx + far + 1] = -1
        i = int(C2.argmax()); sy, sx = divmod(i, C.shape[1]); sec = int(C2[sy, sx])
        if sec <= 0: sec = 0; sx = sy = 0
        kk = float(k * k)
        self.fill = best / kk; self.second = sec / kk
        bX = float(F32((bx + k / 2.0) * s) / F32(w)); bY = float(F32((by + k / 2.0) * s) / F32(h))
        self.secX = float(F32((sx + k / 2.0) * s) / F32(w)); self.secY = float(F32((sy + k / 2.0) * s) / F32(h))
        self.ringY = -1.0
        ok = self.fill >= FOUND_FILL and self.second <= 0.6 * self.fill
        self.discX = bX; self.discY = bY
        if (not ok) and self.fill >= FOUND_FILL and abs(bX - self.secX) <= 0.08 and 0.12 <= abs(bY - self.secY) <= 0.45:
            if bY < self.secY: ok = True; self.ringY = self.secY; self.ringX = self.secX
            elif self.second >= FOUND_FILL: ok = True; self.discX = self.secX; self.discY = self.secY; self.ringY = bY; self.ringX = bX
        if self.ringY >= 0: self.lastRingNs = tNs
        elif ok and tNs - self.lastRingNs < 1_500_000_000 and abs(self.discY - self.ringYLast) < 0.06 and abs(self.discX - self.ringX) < 0.08: ok = False
        if self.ringY >= 0: self.ringYLast = self.ringY
        if not ok: self.discX = -1.0; self.discY = -1.0
        return ok

    def findTop(self, im):
        w = self.w; h = self.h; u = h / 720.0
        cx = int(F32(self.discX) * F32(w)); cy = int(F32(self.discY) * F32(h))
        xa = max(0, cx - int(10 * u)); xb = min(w - 1, cx + int(10 * u))
        yEnd = min(h - 1, cy + int(30 * u))
        cnt = disc_red(im[cy:yEnd + 1, xa:xb + 1]).sum(1)
        bot = cy
        for j in range(len(cnt)):
            y = cy + j
            if cnt[j] >= 3: bot = y
            elif y > bot + 2: break
        self.markBot = bot
        xa = max(0, cx - int(26 * u)); xb = min(w - 1, cx + int(26 * u))
        y0 = bot + int(4 * u); yLast = min(h - 1, bot + int(90 * u))
        if y0 > yLast: return
        px = im[y0:yLast + 1, xa:xb + 1].astype(np.int32)
        b, g, r = px[..., 0], px[..., 1], px[..., 2]
        cnt = ((r >= 140) & (g * 100 <= r * 28) & (b * 100 <= r * 55) & (r - g >= 90)).sum(1)
        hit = np.nonzero(cnt >= 3)[0]
        if len(hit): self.topDy = (y0 + int(hit[0]) - bot) / float(h)

    def findCentre(self, im):
        w = self.w; h = self.h; u = h / 720.0
        bot = self.markBot; cx = int(F32(self.discX) * F32(w))
        ya = max(0, bot - int(44 * u)); xa = max(0, cx - int(40 * u)); xb = min(w - 1, cx + int(40 * u))
        m = disc_red(im[ya:bot + 1, xa:xb + 1])
        rows = m.sum(1)
        n = int(rows.sum())
        self.ycN = n
        if n >= int(150 * u * u): self.ycY = float((rows * np.arange(ya, bot + 1)).sum()) / n / h

    # ---------------------------------------------------------------- crouch, arms up
    def crouch(self, tNs):
        if self.ycY < 0: return
        li = (self.cPos - 1) % CR_N
        if self.cCount == 0 or self.cRunNs < self.runNs or tNs - self.cT[li] > 100_000_000 or abs(self.ycY - self.cY[li]) > 0.06:
            self.cCount = 0; self.cRunNs = tNs
        prevY = self.cY[li] if self.cCount > 0 else self.ycY
        self.cT[self.cPos] = tNs; self.cY[self.cPos] = self.ycY; self.cNn[self.cPos] = self.ycN
        self.cPos = (self.cPos + 1) % CR_N
        if self.cCount < CR_N: self.cCount += 1
        if tNs - self.cRunNs < 700_000_000 or tNs - self.lastCrouchNs < 1_500_000_000: return
        if self.side == OFF or tNs - self.lastDefNs > 1_000_000_000: return
        lo = self.ycY; loNs = tNs
        cMin = 1e9; cMax = -1.0; cn = 0; nSum = 0; cOld = tNs; cNew = 0
        for i in range(self.cCount):
            idx = (self.cPos - 1 - i) % CR_N
            age = tNs - self.cT[idx]
            if age > 700_000_000: break
            if age <= CROUCH_WIN_NS and self.cY[idx] < lo: lo = self.cY[idx]; loNs = self.cT[idx]
            if age >= 200_000_000:
                if cn == 0: cNew = self.cT[idx]
                cn += 1; cOld = self.cT[idx]; nSum += self.cNn[idx]
                cMin = min(cMin, self.cY[idx]); cMax = max(cMax, self.cY[idx])
        if cn < 10 or cNew - cOld < 400_000_000 or cMax - cMin > CROUCH_CALM: return
        sink = self.ycY - lo
        if sink < CROUCH_SINK or self.ycY < prevY: return
        nMean = nSum / cn
        if self.ycN < 0.8 * nMean or self.ycN > 1.25 * nMean: return
        if self.lastStandNs != tNs: return
        self.lastCrouchNs = tNs
        ringKnown = tNs - self.gapNs < 150_000_000 and 0.12 <= self.gapRing - self.discY <= 0.6
        d = "crouch sink=%.4f in=%d calm=%.4f n=%d/%.0f top=%.4f run=%d gap=%.3f xspan=%.3f" % (
            sink, (tNs - loNs) // 1_000_000, cMax - cMin, self.ycN, nMean, self.topDy, (tNs - self.runNs) // 1_000_000,
            self.gapRing - self.discY if ringKnown else -1.0, self.runXMax - self.runXMin)
        self.event(tNs, "crouch", d)
        if not self.crouchPress: return
        why = "gap_pending" if self.pendNs != 0 else "pressed" if tNs - self.lastPressNs < 1_500_000_000 else "lockout" if tNs - self.lastShotFireNs < 1_500_000_000 else ""
        if why: self.event(tNs, "crouch_dropped", why); return
        waitMs = CROUCH_DELAYS[self.crouchSeq % len(CROUCH_DELAYS)]; self.crouchSeq += 1
        self.lastShotFireNs = tNs
        meDist = self.findMe(self.discX, self.gapRing if ringKnown else min(0.95, self.discY + 0.3))
        if not ringKnown and meDist >= 0: meDist = abs(self.meX - self.discX)
        d2 = "%s cd=%d me=%.3f/%.3f d=%.3f" % (d, waitMs, self.meX, self.meY, meDist)
        self.event(tNs, "shot_trigger", d2)
        if meDist > ME_FAR: self.reject(tNs, "rejected_far", d2); return
        self.press(tNs, d2, -waitMs)

    def armsUp(self, tNs):
        if self.topDy < 0 or self.topDy > UP_DY + 0.011: self.lastLowNs = tNs
        if self.topDy > UP_DY + 0.011: self.lastStandNs = tNs
        if 0 <= self.topDy <= UP_DY:
            if self.upRun == 0: self.upSinceNs = tNs
            self.upRun += 1
        else: self.upRun = 0

    def armsUpEvent(self, tNs):
        if self.upRun == 3 and self.side == DEF and self.upSinceNs - self.lastLowNs < 300_000_000 and tNs - self.lastUpEventNs > 1_000_000_000:
            self.lastUpEventNs = tNs
            same = self.lastStandNs >= self.runNs and self.upSinceNs - self.lastStandNs < 300_000_000
            self.event(tNs, "arms_up", "top=%.4f ring=%.3f run=%d xspan=%.3f stand=%d same=%d" % (
                self.topDy, self.gapRing - self.discY if tNs - self.gapNs < 150_000_000 else -1.0, (tNs - self.runNs) // 1_000_000,
                self.runXMax - self.runXMin, (self.upSinceNs - self.lastStandNs) // 1_000_000, 1 if same else 0))
            if same and self.upPress: self.upPendNs = tNs

    def checkUpPending(self, tNs, found):
        if self.upPendNs == 0: return
        age = tNs - self.upPendNs
        if age < UP_HOLD_NS: return
        self.upPendNs = 0
        why = "lost" if (not found or age > 100_000_000) else "down" if self.upRun == 0 else "gap_pending" if self.pendNs != 0 else "pressed" if tNs - self.lastPressNs < 1_500_000_000 else ""
        if why: self.event(tNs, "up_dropped", why); return
        ringKnown = tNs - self.gapNs < 150_000_000 and 0.12 <= self.gapRing - self.discY <= 0.6
        self.lastShotFireNs = tNs
        meDist = self.findMe(self.discX, self.gapRing if ringKnown else min(0.95, self.discY + 0.3))
        if not ringKnown and meDist >= 0: meDist = abs(self.meX - self.discX)
        d2 = "up gap=%.3f top=%.4f upage=%d run=%d xspan=%.3f me=%.3f/%.3f d=%.3f" % (
            self.gapRing - self.discY if ringKnown else -1.0, self.topDy, (tNs - self.upSinceNs) // 1_000_000, (tNs - self.runNs) // 1_000_000,
            self.runXMax - self.runXMin, self.meX, self.meY, meDist)
        self.event(tNs, "shot_trigger", d2)
        if meDist > ME_FAR: self.reject(tNs, "rejected_far", d2); return
        self.press(tNs, d2 + " up", 0)

    def topText(self, tNs):
        return " top=%.4f topage=%d" % (self.topDy, (tNs - self.upSinceNs) // 1_000_000 if self.upRun > 0 else -1)

    # ---------------------------------------------------------------- track and rules
    def onDisc(self, tNs):
        if self.hCount > 0:
            li = (self.hPos - 1) % HN
            if abs(self.discY - self.hY[li]) > 0.15: self.hCount = 0
        if self.runLastNs == 0 or tNs - self.runLastNs > 100_000_000 or abs(self.discX - self.runLastX) > 0.08 or abs(self.discY - self.runLastY) > 0.06:
            self.runNs = tNs; self.runY0 = self.discY; self.runXMin = self.discX; self.runXMax = self.discX; self.runG = 0
        self.runLastNs = tNs; self.runLastX = self.discX; self.runLastY = self.discY
        self.runXMin = min(self.runXMin, self.discX); self.runXMax = max(self.runXMax, self.discX)
        if self.ringY >= 0: self.gapNs = tNs; self.gapRing = self.ringY
        if tNs - self.gapNs < 150_000_000 and 0.12 <= self.gapRing - self.discY <= 0.6:
            g = self.gapRing - self.discY
            if self.runG == 0: self.runG0 = g; self.runG1 = g; self.runRMin = self.gapRing; self.runRMax = self.gapRing
            else:
                if self.runG == 1: self.runG1 = g
                self.runRMin = min(self.runRMin, self.gapRing); self.runRMax = max(self.runRMax, self.gapRing)
            self.runG += 1
            if self.gCount > 0:
                li = (self.gPos - 1) % HN
                if tNs - self.gT[li] > 60_000_000 or abs(g - self.gV[li]) >= 0.07: self.gJumpNs = tNs
            self.gT[self.gPos] = tNs; self.gV[self.gPos] = g
            self.gPos = (self.gPos + 1) % HN
            if self.gCount < HN: self.gCount += 1
        refI = -1
        for i in range(1, min(self.hCount, HN - 1) + 1):
            idx = (self.hPos - i) % HN
            dt = tNs - self.hT[idx]
            if dt > 160_000_000: break
            if dt >= 60_000_000: refI = idx
        if refI >= 0 and tNs - self.lastFastNs > 700_000_000:
            dtS = (tNs - self.hT[refI]) / 1e9
            dx = self.discX - self.hX[refI]; dy = self.discY - self.hY[refI]
            speed = math.sqrt(dx * dx * self.aspect * self.aspect + dy * dy) / dtS
            if speed > FAST_SPEED:
                self.lastFastNs = tNs
                upward = dy < 0 and -dy > 0.5 * abs(dx) * self.aspect
                shotLike = self.side != OFF and tNs - self.lastDefNs < 1_000_000_000 and upward
                self.event(tNs, "disc_fast", "shot_candidate" if shotLike else "upward" if upward else "sideways")
        self.hT[self.hPos] = tNs; self.hX[self.hPos] = self.discX; self.hY[self.hPos] = self.discY
        self.hPos = (self.hPos + 1) % HN
        if self.hCount < HN: self.hCount += 1
        self.checkShot(tNs)

    def ref(self, tNs, lo, hi):
        best = -1; bestErr = 1 << 62; mid = (lo + hi) // 2
        for i in range(1, self.hCount):
            idx = (self.hPos - 1 - i) % HN
            age = tNs - self.hT[idx]
            if age > hi: break
            if age >= lo and abs(age - mid) < bestErr: bestErr = abs(age - mid); best = idx
        return best

    def checkShot(self, tNs):
        if self.side == OFF or tNs - self.lastDefNs > 1_000_000_000 or tNs - self.lastShotFireNs < 1_500_000_000: return
        gT = self.gT; gV = self.gV; hT = self.hT; hX = self.hX; hY = self.hY
        gLast = (self.gPos - 1) % HN
        if self.runG >= 2 and tNs - gT[gLast] <= 1_000_000 and 50_000_000 <= tNs - self.runNs <= 350_000_000:
            fNeed = FRESH_RISE[self.sens]
            g0 = gV[gLast]
            grown = g0 - min(self.runG0, self.runG1)
            up = self.runY0 - self.discY
            still = self.runRMax - self.runRMin <= 0.012 and self.runXMax - self.runXMin < CALM_XSPAN
            if self.runG >= 3 and still and grown >= fNeed - 0.001 and up >= 0.0199:
                g1 = gV[(self.gPos - 2) % HN]; g2 = gV[(self.gPos - 3) % HN]
                if g0 >= g1 - 0.006 and g1 >= g2 - 0.006:
                    self.lastShotFireNs = tNs
                    meDist = self.findMe(self.discX, self.discY + g0)
                    d2 = "fresh gap=%.3f first=%.3f up=%.3f run=%d n=%d xspan=%.3f me=%.3f/%.3f d=%.3f" % (
                        g0, min(self.runG0, self.runG1), up, (tNs - self.runNs) // 1_000_000, self.runG, self.runXMax - self.runXMin, self.meX, self.meY, meDist) + self.topText(tNs)
                    self.event(tNs, "shot_trigger", d2)
                    if meDist > ME_FAR: self.reject(tNs, "rejected_far", d2); return
                    self.press(tNs, d2 + " fresh", 0)
                    return
        a = self.ref(tNs, 60_000_000, 130_000_000)
        if a < 0: return
        b = self.ref(tNs, 200_000_000, 330_000_000)
        if b < 0: return
        dtA = (tNs - hT[a]) / 1e9; dtB = (hT[a] - hT[b]) / 1e9
        if dtA <= 0 or dtB <= 0: return
        vy = (hY[a] - self.discY) / dtA
        vx = (self.discX - hX[a]) * self.aspect / dtA
        vyPrev = (hY[b] - hY[a]) / dtB
        c = self.ref(tNs, 120_000_000, 200_000_000)
        rise = (hY[c] if c >= 0 else hY[a]) - self.discY
        pts = 0
        for i in range(1, self.hCount):
            idx = (self.hPos - 1 - i) % HN
            if tNs - hT[idx] > 600_000_000: break
            pts += 1
        if pts < 5: return
        need = GAP_RISE[self.sens]
        if self.gCount < 6 or tNs - gT[gLast] > 1_000_000: return
        gNow = gV[gLast]
        gOld = 1e9; base = []; lo0 = 1e9; lo1 = 1e9; loN = 0
        for i in range(1, self.gCount):
            idx = (self.gPos - 1 - i) % HN
            age = tNs - gT[idx]
            if age > 600_000_000: break
            if 60_000_000 <= age <= 140_000_000: gOld = min(gOld, gV[idx])
            if age >= 250_000_000: base.append(gV[idx])
            if 60_000_000 <= age <= 400_000_000 and gT[idx] >= self.gJumpNs:
                loN += 1
                v = gV[idx]
                if v < lo0: lo1 = lo0; lo0 = v
                elif v < lo1: lo1 = v
        v2Ready = gOld != 1e9 and len(base) >= 5
        base.sort()
        gBase = base[len(base) // 2] if v2Ready else 0.0
        earlyNeed = EARLY_RISE[self.sens]
        gLow = max(lo0, lo1 - 0.006) if loN >= 5 else 1e9
        early = loN >= 5 and gNow - gLow >= earlyNeed - 0.001
        v2 = v2Ready and gNow - gOld >= need and gNow - gBase >= need
        if not v2 and not early: return
        xMin = self.discX; xMax = self.discX; spanNs = 0
        for i in range(1, self.hCount):
            idx = (self.hPos - 1 - i) % HN
            if tNs - hT[idx] > 600_000_000: break
            xMin = min(xMin, hX[idx]); xMax = max(xMax, hX[idx]); spanNs = tNs - hT[idx]
        xspan = xMax - xMin
        calm = xspan < CALM_XSPAN and spanNs >= 400_000_000
        if not v2 and not calm: return
        self.lastShotFireNs = tNs
        detail = "gap gap=%.3f old=%.3f base=%.3f low=%.3f early=%d vy=%.2f vyPrev=%.2f vx=%.2f rise=%.3f" % (
            gNow, gOld if v2Ready else -1.0, gBase if v2Ready else -1.0, gLow if loN >= 5 else -1.0, 0 if v2 else 1, vy, vyPrev, vx, rise)
        meDist = self.findMe(self.discX, self.discY + gNow)
        d2 = "%s xspan=%.3f track=%d me=%.3f/%.3f d=%.3f" % (detail, xspan, spanNs // 1_000_000, self.meX, self.meY, meDist) + self.topText(tNs)
        self.event(tNs, "shot_trigger", d2)
        moving = xspan >= CONFIRM_XSPAN
        if meDist > ME_FAR: self.reject(tNs, "rejected_far", d2); return
        if calm: self.press(tNs, d2 + " calm", 0 if v2 else -EARLY_DELAY_MS); return
        self.pendNs = tNs; self.pendY = self.discY; self.pendDetail = d2 + " move" if moving else d2
        self.pendRise = MOVE_RISE if moving else CONFIRM_RISE
        self.pendDrop = "rejected_xspan" if moving else "rejected_norise"

    def reject(self, tNs, name, detail):
        self.lastShotFireNs = tNs - 1_100_000_000
        self.event(tNs, name, detail)

    def press(self, tNs, detail, ageMs):
        self.lastPressNs = tNs
        delay = max(0, self.shotDelay - ageMs)
        self.events.append(((tNs - BASE) / 1e9 + delay / 1000.0, "pressed", SIDE[self.side], self.discX, self.discY, detail))

    def checkPending(self, tNs, found):
        if self.pendNs == 0: return
        age = tNs - self.pendNs
        up = self.pendY - self.discY if found else 0.0
        if found and up >= self.pendRise and up < 0.15:
            detail = "%s up=%.3f after=%d" % (self.pendDetail, up, age // 1_000_000)
            self.pendNs = 0
            self.event(tNs, "confirmed", detail)
            self.press(tNs, detail, age // 1_000_000)
        elif age > CONFIRM_WINDOW_NS:
            self.pendNs = 0
            self.reject(tNs, self.pendDrop, "%s up=%.3f" % (self.pendDetail, up))

    def findMe(self, sx, sy):
        self.meX = -1.0; self.meY = -1.0
        w = self.w; h = self.h; s = self.step; gw = self.gw; gh = self.gh
        nw = 16; nh = 6
        ax = min(max(int((sx - 0.3) * gw), 0), gw - 1); bx = min(max(int((sx + 0.3) * gw), 0), gw)
        ay = min(max(int((self.discY + 0.05) * gh), 0), gh - 1); by = min(max(int(min(0.95, sy + 0.3) * gh), 0), gh)
        rw = bx - ax; rh = by - ay
        if rw <= nw or rh <= nh: return -1.0
        p = self.sub[ay:by, ax:bx].astype(np.int16)
        b, g, r = p[..., 0], p[..., 1], p[..., 2]
        hud = self.hud[ay:by, ax:bx]
        navy = (r < 75) & (g < 70) & (b >= 25) & (b <= 120) & (b - r >= 8) & ~hud
        rim = (r < 120) & ~navy & (g >= 90) & (g <= 200) & (b > 165) & (b - r > 80) & ~hud
        sn = np.zeros((rh + 1, rw + 1), np.int32); sn[1:, 1:] = navy.cumsum(0).cumsum(1)
        sb = np.zeros((rh + 1, rw + 1), np.int32); sb[1:, 1:] = rim.cumsum(0).cumsum(1)
        def box(a, x0, y0, x1, y1):
            xa = min(max(x0, 0), rw); xb = min(max(x1, 0), rw); ya = min(max(y0, 0), rh); yb = min(max(y1, 0), rh)
            return int(a[yb, xb] - a[ya, xb] - a[yb, xa] + a[ya, xa])
        best = 0; bxc = -1; byc = -1
        N = sn[nh:, nw:] - sn[:-nh, nw:] - sn[nh:, :-nw] + sn[:-nh, :-nw]
        for y, x in zip(*np.nonzero(N >= nw * nh / 2)):
            y = int(y); x = int(x); n = int(N[y, x])
            if n < nw * nh // 2: continue
            l = box(sb, x - 5, y - 2, x + 3, y + nh + 2)
            if l < 3: continue
            rr = box(sb, x + nw - 3, y - 2, x + nw + 5, y + nh + 2)
            if rr < 3: continue
            if box(sn, x - nw, y - nh, x + 2 * nw, y + 2 * nh) > 2.2 * nw * nh: continue
            sc = n + min(l, 12) + min(rr, 12)
            if sc > best: best = sc; bxc = x; byc = y
        if bxc < 0: return -1.0
        self.meX = (ax + bxc + nw / 2.0) * s / w; self.meY = (ay + byc + nh / 2.0) * s / h
        dx = self.meX - sx; dy = (self.meY - sy) / self.aspect
        return math.sqrt(dx * dx + dy * dy)


ROW_COLS = "t_s,side,disc,x,y,fill,second,dark,orange,slot2_luma,ring_y,top_dy,yc,yc_n"


def run(mp4, sim, t_from=0.0, t_to=1e9):
    v = cv2.VideoCapture(mp4); n = 0
    while True:
        ok, im = v.read()
        if not ok: break
        t = v.get(cv2.CAP_PROP_POS_MSEC) / 1000.0
        n += 1
        if n % 2 != 0 or t < t_from: continue
        if t > t_to: break
        sim.onFrame(im, BASE + int(round(t * 1e9)))
    return sim


def cached(mp4, opts):
    tag = ".sim%s" % opts["tag"]
    rc = mp4[:-4] + tag + ".csv"; ec = mp4[:-4] + tag + "ev.csv"
    if not (os.path.exists(ec) and os.path.getmtime(ec) >= os.path.getmtime(mp4)):
        sim = run(mp4, Sim(opts["sens"], opts["up"], opts["crouch"]))
        with open(rc, "w") as f:
            f.write(ROW_COLS + "\n")
            for r in sim.rows: f.write("%.4f,%s,%d,%.4f,%.4f,%.2f,%.2f,%.3f,%.3f,%.1f,%.4f,%.4f,%.5f,%d\n" % r)
        with open(ec, "w", encoding="utf-8", newline="") as f:
            wr = csv.writer(f); wr.writerow(["t_s", "event", "side", "x", "y", "detail"])
            for e in sorted(sim.events): wr.writerow(["%.4f" % e[0], e[1], e[2], "%.4f" % e[3], "%.4f" % e[4], e[5]])
    ev = [(float(r["t_s"]), r["event"], r["detail"]) for r in csv.DictReader(open(ec, encoding="utf-8"))]
    rows = list(csv.DictReader(open(rc)))
    return rows, ev


def short(name, detail):
    if name == "shot_trigger": return "trig:" + detail.split(" ")[0] + (":early" if " early=1" in detail else "")
    if name == "pressed": return "PRESS" + (":calm" if detail.endswith(" calm") else ":fresh" if detail.endswith(" fresh") else ":up" if detail.endswith(" up") else ":crouch" if detail.startswith("crouch") else ":confirmed")
    if name == "arms_up": return "arms_up" + ("" if "same=1" in detail else "(same=0)")
    return name


def report(mp4, opts):
    rows, ev = cached(mp4, opts)
    e = vidskill.events(vidskill.scan(mp4))
    name = os.path.basename(mp4)[:-4]
    if e is None: print("%s: empty" % name); return []
    def where(t):
        for a, b, k in e["seg"]:
            if a - 0.3 <= t <= b + 0.3: return k
        return "-"
    T = [float(r["t_s"]) for r in rows]
    disc = sum(1 for r in rows if r["disc"] == "1"); df = sum(1 for r in rows if r["side"] == "def")
    print("== %s  %.1f s  scans %d  def %.0f%%  marker %.0f%%  banner: %s" % (name, e["dur"], len(rows), 100.0 * df / max(1, len(rows)), 100.0 * disc / max(1, len(rows)),
          "  ".join("%s %.2f-%.2f" % (k, a, b) for a, b, k in e["seg"]) or "-"))
    marks = []
    for l in e["lits"]:
        if where(l[0]) == "D" and l[1] < 1.0: marks.append((l[0], "D GAME PRESS", 0))
    for b in e["bursts"]:
        if where(b[0]) == "D" and b[2] >= 6000: marks.append((b[0], "D block flash", b[2]))
    for d in e["darks"]:
        marks.append((d["on"], "%s dark onset" % where(d["on"]), 0))
    for t, n, d in ev:
        if n == "sweep_rel": marks.append((t, "%s release step" % where(t), 0))
    marks.sort()
    out = []
    for t, kind, size in marks:
        near = [(x, short(n, d)) for x, n, d in ev if t - 1.6 <= x <= t + 0.35 and n not in ("sweep_rel", "disc_fast", "confirmed")]
        i = bisect.bisect_left(T, t - 0.6); j = bisect.bisect_left(T, t)
        seen = sum(1 for r in rows[i:j] if r["disc"] == "1"); ring = sum(1 for r in rows[i:j] if float(r["ring_y"]) >= 0)
        print("   %-16s %7.3f%s | marker %d/%d ring %d | %s" % (kind, t, " (+%d)" % size if size else "", seen, j - i, ring,
              "  ".join("%s %+d" % (s, round((x - t) * 1000)) for x, s in near) or "nothing"))
        out.append((name, kind, t, near))
    return out


def check(folder, mp4, off):
    """The app's shot.csv / shot_events.csv against this port on a recording of the same minutes."""
    A = list(csv.DictReader(open(os.path.join(folder, "shot.csv"), encoding="utf-8")))
    a0 = float(A[0]["t_s"]) - off; a1 = float(A[-1]["t_s"]) - off
    sim = run(mp4, Sim(), max(0.0, a0), a1)
    ST = [r[0] for r in sim.rows]
    n = 0; both = 0; onlyA = 0; onlyS = 0; dx = []; dy = []; dr = []; dt = []; dc = []; ds = []; ringA = 0; ringS = 0; ringB = 0
    for r in A:
        t = float(r["t_s"]) - off
        i = bisect.bisect_left(ST, t)
        if i <= 0 or i >= len(ST): continue
        s = sim.rows[i] if ST[i] - t < t - ST[i - 1] else sim.rows[i - 1]
        if abs(s[0] - t) > 0.012: continue
        n += 1
        fa = r["disc"] == "1"; fs = s[2] == 1
        ds.append(abs(float(r["slot2_luma"]) - s[9]))
        if fa and fs:
            both += 1
            dx.append(abs(float(r["x"]) - s[3])); dy.append(abs(float(r["y"]) - s[4]))
            ra = float(r["ring_y"]); ringA += ra >= 0; ringS += s[10] >= 0
            if ra >= 0 and s[10] >= 0: ringB += 1; dr.append(abs(ra - s[10]))
            ta = float(r["top_dy"]);
            if ta >= 0 and s[11] >= 0: dt.append(abs(ta - s[11]))
            ya = float(r["yc"])
            if ya >= 0 and s[12] >= 0: dc.append(abs(ya - s[12]))
        elif fa: onlyA += 1
        elif fs: onlyS += 1
    def q(v, p): v = sorted(v); return v[min(len(v) - 1, int(p * len(v)))] if v else float("nan")
    print("scans compared %d (app scan within 12 ms of a sim scan)" % n)
    print("marker: both %d  app only %d  sim only %d" % (both, onlyA, onlyS))
    print("ring among both: app %d  sim %d  both %d" % (ringA, ringS, ringB))
    for nm, v, px in (("x", dx, 1560), ("y", dy, 720), ("ring_y", dr, 720), ("top_dy", dt, 720), ("yc", dc, 720)):
        print("  |d %-6s| median %.4f  90%% %.4f  99%% %.4f  (= %.1f / %.1f / %.1f px)  n=%d" % (nm, q(v, .5), q(v, .9), q(v, .99), q(v, .5) * px, q(v, .9) * px, q(v, .99) * px, len(v)))
    print("  |d slot2 luma| median %.1f  90%% %.1f" % (q(ds, .5), q(ds, .9)))
    AE = [(float(r["t_s"]) - off, r["event"], r["detail"]) for r in csv.DictReader(open(os.path.join(folder, "shot_events.csv"), encoding="utf-8"))]
    names = ("crouch", "arms_up", "shot_trigger", "rejected_xspan", "rejected_norise", "rejected_far", "would_press", "pressed", "sweep_rel")
    print("events (video s): app | sim")
    al = [(t, n, d) for t, n, d in AE if n in names and a0 <= t <= a1 and not d.startswith(("sweep", "dark"))]
    sl = [(t, n, d) for t, n, s_, x, y, d in sorted(sim.events) if n in names]
    for nm in names:
        a = [t for t, n, d in al if n == nm]; s = [t for t, n, d in sl if n == nm]
        m = 0; err = []
        for t in a:
            c = [x for x in s if abs(x - t) <= 0.08]
            if c: m += 1; err.append(min(c, key=lambda x: abs(x - t)) - t)
        print("  %-16s app %3d  sim %3d  matched within 80 ms %3d  sim-app median %+.0f ms" % (nm, len(a), len(s), m, 1000 * q(err, .5) if err else float("nan")))
    return al, sl


if __name__ == "__main__":
    a = sys.argv[1:]
    if a and a[0] == "--check":
        al, sl = check(a[1], a[2], float(a[3]))
        if "-v" in a:
            for t, n, d in sorted(al + [(t, "  SIM " + n, d) for t, n, d in sl]): print("%8.3f %-22s %s" % (t, n, d[:110]))
        sys.exit(0)
    opts = dict(sens=1, up=False, crouch=False, tag="")
    paths = []
    i = 0
    while i < len(a):
        if a[i] == "-s": opts["sens"] = int(a[i + 1]); i += 1
        elif a[i] == "--up": opts["up"] = True
        elif a[i] == "--crouch": opts["crouch"] = True
        else: paths.append(a[i])
        i += 1
    opts["tag"] = ("" if opts["sens"] == 1 else "_s%d" % opts["sens"]) + ("_up" if opts["up"] else "") + ("_cr" if opts["crouch"] else "")
    for x in paths:
        for p in (sorted(glob.glob(os.path.join(x, "*.mp4"))) if os.path.isdir(x) else [x]): report(p, opts)
