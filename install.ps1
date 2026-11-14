# One-line installer for scalino on Windows (x86_64 and arm64).
#
#   irm https://raw.githubusercontent.com/lolgab/scalino/main/install.ps1 | iex
#
# Downloads the latest (or $env:SCALINO_VERSION-pinned) release zip for this machine, verifies its
# sha256, unpacks it into a versioned directory under $env:SCALINO_INSTALL_DIR (default:
# %LOCALAPPDATA%\scalino), points the `current` junction at it and adds `current` to the user's PATH,
# so `scalino` and `scalino-lsp` resolve everywhere. No administrator rights needed.
#
# Nothing else has to be installed: the first build downloads the C toolchain it links with
# (llvm-mingw's clang and lld, about 190 MB) into %LOCALAPPDATA%\scalino\sysroots.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$repo = 'lolgab/scalino'
$version = if ($env:SCALINO_VERSION) { $env:SCALINO_VERSION } else { 'latest' }
$installDir = if ($env:SCALINO_INSTALL_DIR) { $env:SCALINO_INSTALL_DIR } else { Join-Path $env:LOCALAPPDATA 'scalino' }

# The machine's architecture, not the process's: PowerShell may itself be an emulated x64 build on Windows on ARM.
$machine = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture
$arch = switch ($machine) {
  'X64' { 'x86_64' }
  'Arm64' { 'arm64' }
  default { throw "install.ps1: unsupported architecture: $machine" }
}

if ($version -eq 'latest') {
  $release = Invoke-RestMethod "https://api.github.com/repos/$repo/releases/latest"
  $version = $release.tag_name
}
if (-not $version.StartsWith('v')) { $version = "v$version" }

$pkg = "scalino-$version-windows-$arch"
$base = "https://github.com/$repo/releases/download/$version"
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) "scalino-install-$([guid]::NewGuid())"
New-Item -ItemType Directory -Path $tmp | Out-Null
try {
  Write-Host "downloading $pkg.zip"
  Invoke-WebRequest "$base/$pkg.zip" -OutFile "$tmp\$pkg.zip"
  Invoke-WebRequest "$base/$pkg.zip.sha256" -OutFile "$tmp\$pkg.zip.sha256"
  $expected = ((Get-Content "$tmp\$pkg.zip.sha256" -Raw).Trim() -split '\s+')[0].ToLower()
  $actual = (Get-FileHash "$tmp\$pkg.zip" -Algorithm SHA256).Hash.ToLower()
  if ($expected -ne $actual) { throw "install.ps1: checksum mismatch for $pkg.zip (expected $expected, got $actual)" }

  Expand-Archive "$tmp\$pkg.zip" -DestinationPath $tmp
  $dest = Join-Path $installDir $pkg
  if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
  New-Item -ItemType Directory -Path $installDir -Force | Out-Null
  Move-Item "$tmp\$pkg" $dest

  # `current` is a junction (no privilege needed, unlike a symlink), so upgrading is one re-point
  $current = Join-Path $installDir 'current'
  if (Test-Path $current) { (Get-Item $current).Delete() }
  New-Item -ItemType Junction -Path $current -Target $dest | Out-Null
} finally {
  Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if (($userPath -split ';') -notcontains $current) {
  [Environment]::SetEnvironmentVariable('Path', ($userPath.TrimEnd(';') + ';' + $current).TrimStart(';'), 'User')
  Write-Host "added $current to your PATH (open a new terminal to pick it up)"
}
Write-Host "installed scalino $version -> $dest"
Write-Host 'try: scalino run Hello.scala'
