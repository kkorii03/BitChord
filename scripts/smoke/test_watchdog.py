#!/usr/bin/env python3
"""Each stop rule fires when it should and stays quiet just under its limit."""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import watchdog as w  # noqa: E402

CHATTER = "11-06 11:08:15.133  2629  2629 I Registration: x"
APP = "11-06 11:08:15.133  6439  6439 D BitChord: x"


def judge(songs=3, wait=150):
    j = w.Judge(0, songs, wait)
    j.log(1, CHATTER)
    return j


def alive(j, until, step=30):
    """Time passes with the device and the app logging, run.sh moving on as it should."""
    t = j.log_at
    while t < until and not j.stop:
        t += step
        j.log(t, APP)
        j.tick(t)
    return t


class Rules(unittest.TestCase):
    def test_setup_hang(self):
        j = w.Judge(0, 3, 150)
        j.tick(w.SETUP_S)
        self.assertIsNone(j.stop)
        j.tick(w.SETUP_S + 1)
        self.assertEqual(j.stop[:2], ("infra", "setup_hang"))

    def test_logcat_stall(self):
        j = judge()
        j.tick(1 + w.STALL_S)
        self.assertIsNone(j.stop)
        j.tick(2 + w.STALL_S)
        self.assertEqual(j.stop[:2], ("infra", "logcat_stall"))

    def test_app_silent_needs_a_logging_device(self):
        j = judge()
        j.script(1, "a VIEW started")
        t = 1
        while t < w.APP_SILENT_S:
            t += 30
            j.log(t, CHATTER)
            j.script_at = t  # run.sh keeps moving, only the app is quiet
            j.tick(t)
        self.assertIsNone(j.stop)
        j.log(t + 30, CHATTER)
        j.tick(t + 30)
        self.assertEqual(j.stop[:2], ("app", "app_silent"))

    def test_song_stuck(self):
        j = judge()
        j.script(1, "a VIEW started")
        t = alive(j, 1 + 150 + w.SONG_MARGIN_S - 30)
        self.assertIsNone(j.stop)
        alive(j, t + 40)
        self.assertEqual(j.stop[:2], ("infra", "song_stuck"))

    def test_own_crash_keeps_the_exception_line(self):
        j = judge()
        j.log(5, "11-06 11:08:15.133  6439  6439 E AndroidRuntime: FATAL EXCEPTION: main")
        self.assertIsNone(j.stop)
        j.log(5, "11-06 11:08:15.133  6439  6439 E AndroidRuntime: Process: com.dev.bitchord, PID: 6439")
        self.assertIsNone(j.stop)
        j.log(5, "11-06 11:08:15.133  6439  6439 E AndroidRuntime: java.lang.IllegalStateException: boom")
        self.assertEqual(j.stop[:2], ("app", "crash"))
        self.assertEqual(w.clean(j.stop[3]), "AndroidRuntime: java.lang.IllegalStateException: boom")

    def test_other_app_crash_is_not_ours(self):
        j = judge()
        j.log(5, "11-06 11:08:15.133  3000  3000 E AndroidRuntime: FATAL EXCEPTION: main")
        j.log(5, "11-06 11:08:15.133  3000  3000 E AndroidRuntime: Process: com.google.android.gms, PID: 3000")
        j.log(5, "11-06 11:08:15.133  3000  3000 E AndroidRuntime: java.lang.IllegalStateException: boom")
        j.log(6, "11-06 11:08:15.133   500   500 E ActivityManager: ANR in com.android.systemui")
        self.assertIsNone(j.stop)

    def test_anr_and_death(self):
        j = judge()
        j.log(5, "11-06 11:08:15.133   500   500 E ActivityManager: ANR in com.dev.bitchord (com.dev.bitchord/.MainActivity)")
        self.assertEqual(j.stop[:2], ("app", "anr"))
        j = judge()
        j.log(5, "11-06 11:08:15.133   500   500 I ActivityManager: Process com.dev.bitchord (pid 6439) has died: fg TOP")
        self.assertEqual(j.stop[:2], ("app", "crash"))

    def test_key_rejected_twice(self):
        j = judge()
        line = "D SongHearing: song-hear: sent lang=ko until=90 mime=audio/webm kb=1 wait=1ms send=1ms requests=1 http=401 words=null audio=null copy=x"
        j.log(5, line)
        self.assertIsNone(j.stop)
        j.log(6, line.replace("http=401", "http=429"))
        self.assertIsNone(j.stop)
        j.log(7, line.replace("http=401", "http=403"))
        self.assertEqual(j.stop[:2], ("tool", "key_rejected"))

    def test_two_quiet_songs_are_normal_three_are_not(self):
        j = judge(songs=5)
        j.script(1, "a VIEW started")
        j.script(150, "b VIEW started")
        j.script(300, "c VIEW started")  # a and b silent, like runs that then played fine
        j.log(310, "D BitChord: [c] TIMING first audio: 9000ms since track selected")
        j.script(450, "d VIEW started")
        j.script(600, "e VIEW started")
        j.script(750, "smoke: forcing one final track change for the last track summary")
        self.assertIsNone(j.stop)  # d and e silent: two again, the third song in a row never came

        j = judge()
        j.script(1, "a VIEW started")
        j.script(150, "b VIEW started")
        j.script(300, "c VIEW started")
        self.assertIsNone(j.stop)
        j.script(450, "smoke: forcing one final track change for the last track summary")
        self.assertEqual(j.stop[:2], ("app", "no_audio"))

    def test_songs_that_never_start(self):
        j = judge()
        j.script(40, "a VIEW did not start; trying MEDIA_PLAY_FROM_SEARCH")
        j.script(55, "a fallback did not start")
        j.script(90, "b fallback did not start")
        self.assertIsNone(j.stop)
        j.script(125, "c fallback did not start")
        self.assertEqual(j.stop[:2], ("app", "no_start"))

    def test_quiet_songs_with_net_failures_are_infra(self):
        j = judge()
        for t in range(2, 2 + w.NET_INFRA):
            j.log(t, "W BitChord: music browse: proxy failed, retrying directly")
        j.script(10, "a VIEW started")
        j.script(160, "b VIEW started")
        j.script(310, "c VIEW started")
        j.script(460, "smoke: forcing one final track change for the last track summary")
        self.assertEqual(j.stop[:2], ("infra", "no_audio"))

    def test_bot_check_flood_only_before_any_sound(self):
        bot = "W BitChord: x hit YouTube's bot check; this is the network, not the track"
        j = judge()
        for t in range(2, 1 + w.BOT_CHECKS):
            j.log(t, bot)
        self.assertIsNone(j.stop)
        j.log(90, bot)
        self.assertEqual(j.stop[:2], ("infra", "bot_check"))
        j = judge()
        j.log(2, "D BitChord: [a] TIMING first audio: 100ms since track selected")
        for t in range(3, 3 + 2 * w.BOT_CHECKS):
            j.log(t, bot)
        self.assertIsNone(j.stop)

    def test_slow_proxy_alone_never_stops_a_run(self):
        j = judge()
        for t in range(2, 60):
            j.log(t, "W BitChord: retrying: Socket timeout has expired")
        self.assertIsNone(j.stop)

    def test_lyric_lines_are_never_read(self):
        j = judge()
        j.log(5, "E BitChord: sync-dump: ANR in com.dev.bitchord")
        self.assertIsNone(j.stop)

    def test_hard_limit_from_song_count(self):
        j = judge(songs=3, wait=150)
        self.assertEqual(j.hard, w.SETUP_S + 3 * 210 + w.POST_S)
        j.stage, j.log_at = "post", j.hard + 1
        j.tick(j.hard + 1)
        self.assertEqual(j.stop[:2], ("tool", "over_budget"))

    def test_scenario_only_has_the_hard_limit(self):
        j = w.Judge(0, 0, 150, scenario=True)
        j.log(1, "I ActivityManager: Process com.dev.bitchord (pid 6439) has died: fg TOP")
        j.tick(w.SCENARIO_HARD_S)
        self.assertIsNone(j.stop)
        j.tick(w.SCENARIO_HARD_S + 1)
        self.assertEqual(j.stop[:2], ("tool", "over_budget"))

    def test_clean_drops_key_shaped_text(self):
        self.assertEqual(w.clean("11-06 11:08:15.133  6439  6439 W BitChord: key=" + "a" * 40), "BitChord: key=***")


if __name__ == "__main__":
    unittest.main()
