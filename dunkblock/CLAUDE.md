# DunkBlock

Read `HANDOFF.md` in this folder first: goal, constraints, measured facts, failed approaches, next steps.

- Android code compiles only in CI (`.github/workflows/dunkblock-apk.yml`); locally run `DUNKBLOCK_CORE_ONLY=1 ./gradlew :core:test`.
- To hand the user an APK, put `[ship-blocker]` (or `[ship-bench]`, `[ship-apk]`) in the commit message; CI commits it to `dist/`.
- The user is not a developer and writes Korean. Answer in Korean, give step-by-step actions, separate measured from estimated.
- Update `HANDOFF.md` sections 3–6 whenever something is learned or built.
