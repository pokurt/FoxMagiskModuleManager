# Fox's Magisk Module Manager (Developer documentation)

Note: This doc assume you already read the
[official Magisk module developer guide](https://topjohnwu.github.io/Magisk/guides.html)

Note: official repo do not accept new modules anymore, submit
[here](https://github.com/Magisk-Modules-Alt-Repo/submission) instead.

Index:
- [Special notes](DEVELOPERS.md#special-notes)
- [Properties](DEVELOPERS.md#properties)
- [ANSI Styling](DEVELOPERS.md#ansi-styling)
- [Installer commands](DEVELOPERS.md#installer-commands)
- [Developer mode](DEVELOPERS.md#developer-mode)
- [End note](DEVELOPERS.md#end-note)

## Special notes

MitM: Certificate pinning is only available since Android 7.0, 
any issue regarding MitM that can only be performed of 
Android versions that doesn't support this feature will be ignored. 

App hiding: I don't intent on hiding the app, the package names should always be 
`com.fox2code.mmm` or starts with `com.fox2code.mmm.`, however I notice the presence of 
my app is causing issues due to it existing, I may add an hiding feature to the app.

Low quality module filter: Implemented at `com.fox2code.mmm.utils.io.PropUtils.isLowQualityModule`, 
it is a check that verify that the module is declaring the minimum required to 
allow the app to show your module to the user without hurting his experience.  
Filling all basic Magisk properties is often enough to not get filtered out by it.

## Custom Repo format

Note: This feature is for `0.6.0` version that is not released yet.

`last_update` fields uses unix millis.

Json format is
```json
{
  "name": "Repo name",
  "website": "repo website",
  "support": "optional support url",
  "donate": "optional support url", 
  "submitModule": "optional submit module URL",
  "last_update": 0,
  "modules": [
    {
      "id": "module id",
      "last_update": 0,
      "notes_url": "notes url",
      "prop_url": "module.prop url",
      "zip_url": "module.zip url"
    }
  ]
}
```

## Properties

In addition to the following required magisk properties
```properties
# Magisk supported properties
id=<string>
name=<string>
version=<string>
versionCode=<int>
author=<string>
description=<string>
```
Note: The Fox's mmm will not show the module if theses values are not filled properly

This the manager support these new optional properties
```properties
# Fox's Mmm supported properties
minApi=<int>
maxApi=<int>
minMagisk=<int>
needRamdisk=<boolean>
support=<url>
donate=<url>
config=<package>
changeBoot=<boolean>
mmtReborn=<boolean>
```
Note: All urls must start with `https://`, or else will be ignored
Note²: For `minMagisk`, `XX.Y` is parsed as `XXY00`, so you can just put the Magisk version name.

- `minApi` and `maxApi` tell the manager which is the SDK version range the module support  
  (See: [Codenames, Tags, and Build Numbers](https://source.android.com/setup/start/build-numbers))
- `minMagisk` tell the manager which is the minimum Magisk version required for the module
  (Often for magisk `xx.y` the version code is `xxyzz`, `zz` being non `00` on canary builds)
- `needRamdisk` tell the manager the module need boot ramdisk to be installed
- `support` support link to direct users when they need support for you modules
- `donate` donate link to direct users to where they can financially support your project
- `config` package name of the application that configure your module
  (Note: The icon won't appear in the module list if the module or target app is not installed)
- `changeBoot` tell the manager the module may change the boot image
- `mmtReborn` tell the manager to use [MMT-Reborn](https://github.com/iamlooper/MMT-Reborn) logging style  
  (For example `- Hello world` will be transformed to `[*] Hello world`, do not apply to modules installed from storage)

Note: Fox's Mmm use fallback 
[here](../app/src/main/java/com/fox2code/mmm/utils/io/PropUtils.java#L36)
for some modules  
Theses values are only used if not defined in the `module.prop` files  
So the original module maker can still override them

## ANSI Styling

FoxMMM declare `ANSI_SUPPORT` to `true` if ANSI is supported.

It use [AndroidANSI](https://github.com/Fox2Code/AndroidANSI) library,
please check it's [README.md](https://github.com/Fox2Code/AndroidANSI/blob/master/README.md)
for the list of supported codes.

## Installer commands

FoxMmm also allow better control over it's installer interface

FoxMmm define the variable `MMM_EXT_SUPPORT` to expose it's extensions support

All the commands start with it `#!`, by default the manager process command as log output
unless `#!useExt` is sent to indicate that the app is ready to use commands

Commands:
- `useExt`: Tell the manager you would like to use commands  
  (Note: Any command executed before this one will just appear as log in the console)
- `addLine <arg>`: Add line to the terminal, this commands can be useful if 
  you want to display text that start with `#!` inside the terminal
- `setLastLine <arg>`: Set the last line of text displayed in the terminal  
  (Note: If the terminal is empty it will just add a new line)
- `clearTerminal`: Clear the terminal of any text, making it empty
- `scrollUp`: Scroll up at the top of the terminal
- `scrollDown`: Scroll down at the bottom of the terminal
- `showLoading <max>`: Show a progress bar with `<max>` as max value
  (Note: Status bar is indeterminate if nothing or 0 is provided)
- `setLoading <progress>`: Set loading progress if the bar is not indeterminate.
- `hideLoading`: Hide the indeterminate progress bar if previously shown
- `setSupportLink <url>`: Set support link to show when the install finish  
  (Note: Modules installed from repo will not show the config button if a link is set)
- `disableANSI`: Disable ANSI support if enabled

Variables:
- `MMM_EXT_SUPPORT` declared if extensions are supported
- `MMM_USER_LANGUAGE` the current user selected language
- `MMM_APP_VERSION` display version of the app (Ex: `x.y.z`)
- `MMM_TEXT_WRAP` is set to `1` if text wrapping is enabled

Note: 
The current behavior with unknown command is to ignore them, 
I may add or remove commands/variables in the future depending of how they are used

A wrapper script to use theses commands could be
```sh
if [ -n "$MMM_EXT_SUPPORT" ]; then
  ui_print "#!useExt"
  mmm_exec() { 
    ui_print "$(echo "#!$@")"
  }
else
  mmm_exec() { true; }
fi
```
And there is an instance of it in use
```sh
# mmm_exec only take effect if inside the loader
mmm_exec showLoading
ui_print "The installer doesn't support mmm_exec"
mmm_exec setLastLine "The installer support mmm_exec"
# Wait to simulate the module doing something
sleep 5
mmm_exec hideLoading
mmm_exec setSupportLink https://github.com/Androidacy/MagiskModuleManager
```

[You may look at the examples modules and their codes.](examples)

## Developer mode

FoxMMM contain a developer mode to unlock unstable, dangerous, or experimental features.

To unlock developer mode you must do the following in this order:
- Go to setting
- Open theme selector option
- Close it without selecting a theme
- Click on show license
- Go back to setting page
- Click on source code button

To disable dev mode just repeat the exact same steps.

## End Note

Have fun with the API making your user install experience a unique experience

Also there is the source of the app icon
[here](https://romannurik.github.io/AndroidAssetStudio/icons-launcher.html#foreground.type=clipart&foreground.clipart=extension&foreground.space.trim=0&foreground.space.pad=0.25&foreColor=rgb(255%2C%20255%2C%20255)&backColor=rgb(255%2C%20152%2C%200)&crop=0&backgroundShape=circle&effects=elevate&name=ic_launcher)
.
