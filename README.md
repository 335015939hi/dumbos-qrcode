# DumbosCode

A single-activity Android system app for GrapheneOS/AOSP builds.

It can:

- scan a QR code using the rear camera;
- accept the same value through a manual text field;
- select `argv[1]` from an in-app dropdown;
- execute the value as one argument to `dumbos`;
- stream stdout into the activity while the command is running;
- drain and discard stderr;
- report success or failure in a dialog.

## Command line

The Java code deliberately constructs this exact command list:

```text
argv[0] = "dumbos"
argv[1] = selected_subcommand ("code" or "ok")
argv[2] = scanned_or_manually_entered_text
```

No shell is involved. Consequently, shell metacharacters in the scanned value
are not interpreted. The entire scanned/manual value is one argument.

To add another dropdown option, edit only `SUBCOMMANDS` near the top of
`MainActivity.java`:

```java
private static final String[] SUBCOMMANDS = {
        "code",
        "ok",
        "another-command",
};
```

`ProcessBuilder` resolves `dumbos` through the app process's `PATH`. A normal
Android app process has `/system/bin` available, so a binary installed as
`/system/bin/dumbos` is the intended arrangement.

## Exit status versus errno

A process has an exit status. POSIX does not require nonzero process exit
statuses to equal `errno` values.

This app follows your requested convention: it treats a nonzero exit status as
an errno number and displays Android's `OsConstants.errnoName()` and
`Os.strerror()` result when available. Therefore, `dumbos` should do something
like:

```c
return 0;       // success
return EACCES;  // failure by this project's convention
```

Keep the value in the range representable by a process exit status.

## Add it to the source tree

A reasonable location is:

```text
packages/apps/DumbosCode/
```

Copy this entire directory there.

Add the module to the appropriate product makefile:

```make
PRODUCT_PACKAGES += DumbosCode
```

Then build it directly:

```sh
source build/envsetup.sh
lunch husky-cur-userdebug   # or your actual target
m DumbosCode
```

After adding it to `PRODUCT_PACKAGES`, a normal product build includes it:

```sh
m
```

## ZXing dependency

The `Android.bp` uses:

```bp
static_libs: ["zxing-core"],
```

AOSP carries that module under `external/zxing`. No Gradle, Maven, Play
services, AAR, or network download is required.

## SELinux warning

Platform signing and `privileged: true` do not grant permission to execute an
arbitrary system binary.

The app process domain must be allowed to find and execute the SELinux type
assigned to `/system/bin/dumbos`. Depending on your existing policy, you may
also want a dedicated app domain and a domain transition into the existing
`dumbos` client domain.

Do not blindly add broad `platform_app` execute permissions. That is the sort
of shortcut that turns a narrowly-scoped utility into a system-wide footgun.
Use the actual file type and app domain from your policy.

A denial will look roughly like:

```text
avc: denied { execute } ... path="/system/bin/dumbos" ...
```

Use the source and target contexts from the actual denial when writing policy.

## Behavior notes

- Scanning a valid QR code immediately runs it with the currently selected
  argv[1] dropdown value.
- Manual input runs when the **Run code** button is pressed.
- Only one command may run at a time.
- stdout is decoded as UTF-8 and appended as data arrives.
- stderr is continuously drained into a discard buffer so the child cannot
  deadlock on a full stderr pipe.
- Destroying or closing the activity does not terminate a still-running child process.
  The command worker continues draining stdout and stderr until the child exits.
  Once the activity is gone, further stdout and the completion dialog are not shown.
