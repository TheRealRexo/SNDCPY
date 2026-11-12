; SNDCPY Windows Client - Inno Setup Script
; Creates a professional MSI-style installer

[Setup]
AppId={{A1B2C3D4-E5F6-7890-ABCD-EF1234567890}}
AppName=SNDCPY
AppVersion=1.0.0
AppPublisher=TheRealRexo
AppPublisherURL=https://github.com/TheRealRexo/SNDCPY
AppSupportURL=https://github.com/TheRealRexo/SNDCPY
AppUpdatesURL=https://github.com/TheRealRexo/SNDCPY/releases
DefaultDirName={autopf}\SNDCPY
DefaultGroupName=SNDCPY
AllowNoIcons=yes
OutputDir=dist
OutputBaseFilename=SNDCPY-Setup-1.0.0
Compression=lzma
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=lowest
UninstallDisplayIcon={app}\SNDCPY.exe
SetupIconFile=sndcpy.ico

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "sndcpy_client.py"; DestDir: "{app}"; Flags: ignoreversion
Source: "requirements.txt"; DestDir: "{app}"; Flags: ignoreversion
Source: "sndcpy.ico"; DestDir: "{app}"; Flags: ignoreversion
Source: "sndcpy.png"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\SNDCPY"; Filename: "{app}\SNDCPY.exe"; WorkingDir: "{app}"; IconFilename: "{app}\sndcpy.ico"
Name: "{group}\{cm:UninstallProgram,SNDCPY}"; Filename: "{uninstallexe}"
Name: "{commondesktop}\SNDCPY"; Filename: "{app}\SNDCPY.exe"; Tasks: desktopicon; IconFilename: "{app}\sndcpy.ico"

[Run]
Filename: "{app}\SNDCPY.exe"; Description: "{cm:LaunchProgram,SNDCPY}"; Flags: nowait postinstall skipifsilent

[Code]
function InitializeSetup(): Boolean;
begin
  Result := True;
end;