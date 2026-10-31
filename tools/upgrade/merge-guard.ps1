# Supervisor check for upstream-upgrade work: finds feature downgrades and
# DeepSeek shortcuts in a candidate commit before it is accepted.
# Usage (repo root): merge-guard.ps1 -Candidate <rev> [-Base <rev>] [-Fork origin/task/fix-1030] [-Upstream v1.9]
#   Base     = the commit the candidate was built on (default: its first parent)
#   Fork     = our fork before the upgrade; Upstream = the upstream tag being adopted
# (Variables avoid the parameter names: PowerShell names are case-insensitive.)
# Prints one section per check; "OK" when a check finds nothing. Exit code 1 when
# any BLOCK finding exists (deleted upstream files, files reverted to the fork).
param(
    [Parameter(Mandatory)][string]$Candidate,
    [string]$Base,
    [string]$Fork = 'origin/task/fix-1030',
    [string]$Upstream = 'v1.9',
    # A file losing more lines than this relative to Base is listed for review.
    [int]$BigDeletion = 150
)
$ErrorActionPreference = 'Continue'
if (-not $Base) { $Base = "$Candidate~1" }
$block = 0

function Paths([string]$rev) { $h = @{}; foreach ($p in git ls-tree -r --name-only $rev) { $h[$p] = 1 }; $h }
$upSet = Paths $Upstream
$forkSet = Paths $Fork
$candSet = Paths $Candidate

"== 1. upstream files deleted (BLOCK: v1.9 features removed)"
$gone = @($upSet.Keys | Where-Object { -not $candSet.ContainsKey($_) } | Sort-Object)
if ($gone.Count) { $block++; $gone | ForEach-Object { "  $_" } } else { '  OK' }

"== 2. files reverted to the pre-upgrade fork in this change (BLOCK: upstream side undone)"
$rev = @()
foreach ($p in git diff --name-only $Base $Candidate) {
    if (-not $upSet.ContainsKey($p) -or -not $forkSet.ContainsKey($p) -or -not $candSet.ContainsKey($p)) { continue }
    $c = git rev-parse "${Candidate}:$p"
    if ($c -eq (git rev-parse "${Fork}:$p") -and $c -ne (git rev-parse "${Upstream}:$p")) { $rev += $p }
}
if ($rev.Count) { $block++; $rev | ForEach-Object { "  $_" } } else { '  OK' }

"== 3. test files that existed in the fork or upstream but are gone (review: lost guards)"
$tests = @(@($forkSet.Keys) + @($upSet.Keys) | Sort-Object -Unique |
    Where-Object { $_ -match '/src/(test|jvmTest|commonTest|androidTest)/' -and -not $candSet.ContainsKey($_) })
if ($tests.Count) { $tests | ForEach-Object { "  $_" } } else { '  OK' }

"== 4. settings keys from the fork or upstream that no code mentions any more (review: lost setting / reset user value)"
$settings = 'app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt'
$keys = @{}
foreach ($r in $Fork, $Upstream) {
    foreach ($m in [regex]::Matches(((git show "${r}:$settings") -join "`n"), 'const val (KEY_\w+)\s*=\s*"([^"]+)"')) { $keys[$m.Groups[2].Value] = $m.Groups[1].Value }
}
$now = (git show "${Candidate}:$settings") -join "`n"
$lost = @($keys.Keys | Where-Object { $now -notmatch [regex]::Escape("`"$_`"") } | Sort-Object)
if ($lost.Count) { $lost | ForEach-Object { "  $_ ($($keys[$_]))" } } else { '  OK' }

"== 5. string resource keys lost (review)"
foreach ($res in 'app/src/main/res/values/strings.xml', 'app/src/main/res/values-ko/strings.xml', 'sharedUi/src/commonMain/composeResources/values/strings.xml') {
    $names = @{}
    foreach ($r in $Fork, $Upstream) {
        foreach ($m in [regex]::Matches(((git show "${r}:$res" 2>$null) -join "`n"), '<(?:string|plurals|string-array)\b[^>]*\bname="([^"]+)"')) { $names[$m.Groups[1].Value] = 1 }
    }
    if (-not $names.Count) { continue }
    $text = (git show "${Candidate}:$res" 2>$null) -join "`n"
    $miss = @($names.Keys | Where-Object { $text -notmatch [regex]::Escape("name=`"$_`"") })
    "  $res : $($miss.Count) missing$(if ($miss.Count) { ' -> ' + (($miss | Select-Object -First 15) -join ', ') })"
}

"== 6. big deletions vs base (review: >$BigDeletion lines removed)"
$big = foreach ($s in git diff --numstat $Base $Candidate) {
    $a, $d, $p = $s -split "`t"
    if ($d -ne '-' -and [int]$d -gt $BigDeletion) { "  -$d +$a $p" }
}
if ($big) { $big } else { '  OK' }

"== 7. [KO] hooks lost vs base (review)"
$had = @{}; foreach ($l in git grep -h -F '[KO]' $Base -- '*.kt') { $had[$l.Trim()] = 1 }
$has = @{}; foreach ($l in git grep -h -F '[KO]' $Candidate -- '*.kt') { $has[$l.Trim()] = 1 }
$lostHooks = @($had.Keys | Where-Object { -not $has.ContainsKey($_) })
if ($lostHooks.Count) { $lostHooks | ForEach-Object { "  $_" } } else { '  OK' }

"BLOCK findings: $block"
if ($block) { exit 1 }
