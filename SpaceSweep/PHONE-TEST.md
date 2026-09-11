# Install the GitHub Actions test APK

Open the **SpaceSweep test APK** workflow run in GitHub Actions. After success,
download the **SpaceSweep-test-apk** artifact, extract the ZIP, and transfer
**SpaceSweep-test.apk** to your Android phone. Open it and allow installation
from that file manager if Android asks. Android 11 or newer is required.

This is a debug-signed test build of `com.verve.spacesweep`; it installs separately
from SpaceWise. Start with disposable copied photos/videos. Deletion is permanent.
The build verifies compilation, the safety tests, and APK signing; it does not
prove real-device permissions, layout or deletion behavior.

Each fresh hosted runner may generate a different debug signing key. If Android
rejects a later build as an incompatible update, uninstall the old test app and
install the new APK. This resets the app's saved folder grants; it does not remove
shared photos and videos. A stable private signing key is needed before regular updates.

SDK licence acceptance for this build was approved by the user in this conversation.
