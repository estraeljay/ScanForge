# Release checklist

- [ ] Choose and add the project LICENSE; align the Blender manifest licence
- [ ] Android: builds in Android Studio with pinned versions refreshed; no warnings-as-errors regressions
- [ ] Android: run on ≥ 1 Depth API device, ≥ 1 non-depth ARCore device, ≥ 1 non-ARCore device, 1 tablet
- [ ] Execute docs/testing-plan.md §2 and record results
- [ ] Execute the physical accuracy protocol (§3); publish results; only then state any accuracy figure
- [ ] Verify AndroidKeyStore TLS server on Android 8, 10, 14+ devices against the Blender add-on
- [ ] `cd core && gradle test` green; `scripts/test_blender.sh` green; framework check green
- [ ] Blender: test 5.2 LTS on Windows, macOS, Linux; firewall prompts documented; decide `blender_version_min`
- [ ] Privacy policy (camera, local storage, local-network transfer, ARCore disclosure); no analytics/uploads
- [ ] Play Console: foreground service (dataSync) declaration and justification; camera permission rationale
- [ ] Signed release bundle; versionCode/versionName bumped; changelog
- [ ] `dist/scanforge3d-<version>.zip` built and installed on a clean Blender
- [ ] Sample project and synthetic fixtures regenerated (`scripts/make_fixtures.sh`)
