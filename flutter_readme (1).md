# Flutter Version Management Guide (macOS / Linux / Windows)

> **Purpose:** Reproducible Flutter setups across machines and projects. Commit this file as `flutter-readme.md` in each repo.

---

## TL;DR (Quick Start)

1. **Install the Dart SDK** if not already installed.
2. **Install FVM** (Flutter Version Manager).
3. **Add ************pub-cache/bin************ to your PATH** if you installed FVM via `dart pub`.
4. **Install the Flutter version** you need with FVM.
5. **Pin the version per project** so IDEs and CLIs use that SDK.
6. **Configure IDEs** (Android Studio / VS Code) to point to `.fvm/flutter_sdk`.
7. **Commit the version pin** (config) but **ignore** the actual SDK folder.
8. **Update** by installing a new version with FVM, then repinning.

---

## Why FVM?

- Keep **multiple Flutter versions** side‑by‑side.
- **Per‑project pinning** for stable builds.
- Works on **macOS, Linux, and Windows**.
- Keeps Dart in sync with Flutter (Dart is bundled).

---

## 1) Install the Dart SDK

Before you can install FVM, ensure the **Dart SDK** is installed on your system.

### macOS

**With Homebrew:**

```bash
brew tap dart-lang/dart
brew install dart
```

**Verify installation:**

```bash
dart --version
```

### Linux (Ubuntu/Debian example)

```bash
sudo apt update -y
sudo apt install apt-transport-https -y
sudo sh -c 'wget -qO- https://dl-ssl.google.com/linux/linux_signing_key.pub | apt-key add -'
echo "deb [arch=$(dpkg --print-architecture)] https://storage.googleapis.com/download.dartlang.org/linux/debian stable main" | sudo tee /etc/apt/sources.list.d/dart_stable.list
sudo apt update -y
sudo apt install dart -y
```

**Verify installation:**

```bash
dart --version
```

### Windows

1. Download and install from [https://dart.dev/get-dart](https://dart.dev/get-dart).
2. During installation, check “Add Dart to PATH.”
3. Verify:

```powershell
dart --version
```

> Once Dart is installed, continue to the FVM installation section below.

---

## 2) Install FVM

### macOS (choose one)

**A. Homebrew**

```bash
brew tap leoafarias/fvm
brew install fvm
```

**B. Dart (cross‑platform method)**

```bash
# FVM is installed globally via pub
dart pub global activate fvm
```

### Linux (Dart method; works everywhere)

```bash
# FVM via pub
dart pub global activate fvm
```

### Windows (PowerShell)

```powershell
# Install FVM via pub
dart pub global activate fvm
```

---

### Add `pub-cache/bin` to PATH (all systems)

If you installed FVM via `dart pub global activate fvm`, you **must** add the Dart pub cache bin directory to your PATH so the `fvm` command is available everywhere.

**What to add**

- **macOS/Linux:** `~/.pub-cache/bin`
- **Windows:** `%LOCALAPPDATA%\Pub\Cache\bin`

**macOS (zsh / default on recent macOS)**

```bash
echo 'export PATH="$PATH:$HOME/.pub-cache/bin"' >> ~/.zshrc
source ~/.zshrc
```

**macOS (bash / older setups)**

```bash
echo 'export PATH="$PATH:$HOME/.pub-cache/bin"' >> ~/.bash_profile
source ~/.bash_profile
```

**macOS/Linux (fish shell)**

```fish
set -Ux fish_user_paths $fish_user_paths $HOME/.pub-cache/bin
```

**Linux (bash)**

```bash
echo 'export PATH="$PATH:$HOME/.pub-cache/bin"' >> ~/.bashrc
source ~/.bashrc
```

**Linux (zsh)**

```bash
echo 'export PATH="$PATH:$HOME/.pub-cache/bin"' >> ~/.zshrc
source ~/.zshrc
```

**Windows (PowerShell, per-user persistent)**

```powershell
# Make fvm available in future PowerShell sessions
"`n`$env:Path += ';' + [Environment]::GetFolderPath('LocalApplicationData') + '\\Pub\\Cache\\bin'" | Add-Content $PROFILE
# Current session only
$env:Path += ";$env:LOCALAPPDATA\Pub\Cache\bin"
```

**Windows (Command Prompt / per-user environment)**

```cmd
setx PATH "%PATH%;%LOCALAPPDATA%\Pub\Cache\bin"
```

> After changing PATH, **restart your terminal** (or `source` your shell config) before running `fvm`.

**Verify FVM is on PATH:**

```bash
which fvm   # macOS/Linux
# or
Get-Command fvm  # Windows PowerShell
```

---

## ✅ Verify Installation

After installing both Dart **and** FVM (and updating PATH if needed), verify your setup before proceeding:

```bash
# Check Dart
which dart
dart --version

# Check FVM
which fvm
fvm --version

# Check PATH variables
echo $PATH | grep pub-cache
```

If all commands return valid paths and versions, your environment is ready for Flutter version management.

---

## 3) Install Flutter versions with FVM

- List available Flutter releases/channels:

```bash
fvm releases
```

- Install a specific version (example):

```bash
fvm install 3.24.2
```

- Or install a channel (stable/beta/master):

```bash
fvm install stable
```

FVM stores SDKs here:

- **macOS/Linux:** `~/.fvm/versions/<version>/`
- **Windows:** `%USERPROFILE%\.fvm\versions\<version>\`

---

## 4) Pin a Flutter version per project

In your project root:

```bash
# Choose the version you installed earlier
fvm use 3.24.2
```

> **Note:** In FVM 4.x and later, the `--save` flag is no longer needed. The configuration is automatically written to `.fvm/fvm_config.json`.

This creates:

- `.fvm/fvm_config.json` (the version lock; **commit this file**)
- `.fvm/flutter_sdk` (a symlink to the selected SDK; **do not commit**)

Add to `.gitignore` (root of your repo):

```
.fvm/flutter_sdk
```

---

## 5) IDE Setup Prerequisites

Even though FVM manages your Flutter SDK versions, both **Android Studio** and **VS Code** still require their Flutter and Dart plugins to provide IDE integration.

### 🧩 Why the plugins are still needed

Even though FVM manages Flutter SDK versions for you, **the IDEs rely on their plugins** for:

- syntax highlighting, autocompletion, and hot reload,
- Flutter/Dart command integrations (run, debug, format),
- automatic detection of the SDK and project structure.

So FVM provides the SDK; the plugins make your IDE *understand and use it.*



### Android Studio

1. Open **Preferences → Plugins**.
2. Search for and install:
   - **Flutter**
   - **Dart** (automatically installed with Flutter if not already present).
3. Restart Android Studio.
4. Go to **Preferences → Languages & Frameworks → Flutter** and set the SDK path to:
   ```
   <project>/.fvm/flutter_sdk
   ```
5. The Dart plugin automatically uses the bundled Dart SDK from your Flutter SDK.

### VS Code

1. Open **Extensions (⇧⌘X)**.
2. Install:
   - **Flutter** (this automatically installs the Dart extension).
3. Create or update `.vscode/settings.json` with:
   ```json
   {
     "dart.flutterSdkPath": ".fvm/flutter_sdk",
     "dart.sdkPath": ".fvm/flutter_sdk/bin/cache/dart-sdk"
   }
   ```

---

## 6) Use the pinned SDK (CLI & IDE)

### CLI (any OS)

Either **prefix commands with FVM**:

```bash
fvm flutter doctor
fvm flutter pub get
fvm flutter run
fvm dart --version
```

Or call the local SDK directly:

```bash
./.fvm/flutter_sdk/bin/flutter doctor
```

### Android Studio

1. **Preferences/Settings → Languages & Frameworks → Flutter**
2. Set **Flutter SDK path** to:

```
<project>/.fvm/flutter_sdk
```

3. Apply. The Dart plugin will use the bundled Dart automatically.

### VS Code

Create or edit `.vscode/settings.json`:

```json
{
  "dart.flutterSdkPath": ".fvm/flutter_sdk",
  "dart.sdkPath": ".fvm/flutter_sdk/bin/cache/dart-sdk"
}
```

---

## 6) Updating Flutter when new versions are available

1. See what’s available:

```bash
fvm releases
```

2. Install the new version (example):

```bash
fvm install 3.27.0
```

3. Switch your project to it:

```bash
fvm use 3.27.0
```

> **Note:** The `--save` flag is no longer necessary in FVM 4.x. The change is saved automatically.

4. Refresh deps & rebuild:

```bash
fvm flutter pub get
fvm flutter clean
# iOS projects only
rm -rf ios/Pods ios/Podfile.lock
( cd ios && pod repo update && pod install )
```

> **Tip:** Avoid `flutter upgrade` inside FVM-managed SDKs. Prefer `fvm install <version>` + `fvm use`.

---

## 7) Team & CI setup

**Commit**

- ✅ `.fvm/fvm_config.json`
- ✅ This `flutter-readme.md`
- ❌ `.fvm/flutter_sdk` (ignored)

**CI example (macOS/Linux)**

```bash
# Install FVM (example using pub)
dart pub global activate fvm
export PATH="$PATH:$HOME/.pub-cache/bin"
# Read version from .fvm/fvm_config.json and install
fvm install
fvm flutter pub get
fvm flutter build apk   # or ios, appbundle, ipa, web, windows, macos, linux
```

**CI example (Windows, PowerShell)**

```powershell
# Install FVM via pub
dart pub global activate fvm
$env:Path += ";$env:LOCALAPPDATA\Pub\Cache\bin"
# Install project version
fvm install
fvm flutter pub get
fvm flutter build windows
```

---

## 8) Troubleshooting

### Recover: `fvm` not found after choosing Homebrew (macOS)

If you removed the pub-installed FVM but `fvm` still isn’t found, reinitialize Homebrew in your shell and verify PATH:

```bash
# 1) Ensure brew is initialized for your shell (Apple Silicon default path shown)
echo 'eval "$

```
