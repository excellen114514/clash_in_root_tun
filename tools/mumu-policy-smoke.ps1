param([Parameter(Mandatory)][string]$Serial, [Parameter(Mandatory)][ValidateSet('DIRECT','FOLLOW')][string]$Policy)
# Run from the status tab with the local QA subscription active. Changes only the test probe's choice.
$ErrorActionPreference='Stop'
$adb=Join-Path $PSScriptRoot '../android-sdk/platform-tools/adb.exe'
function Tree { & $adb -s $Serial shell uiautomator dump /data/local/tmp/roottun-ui.xml | Out-Null; [xml](& $adb -s $Serial shell cat /data/local/tmp/roottun-ui.xml) }
function Tap($node) { if (!$node) { throw 'Expected control is not visible' }; $p=[regex]::Matches($node.bounds,'\d+') | ForEach-Object {[int]$_.Value}; & $adb -s $Serial shell input tap ([int](($p[0]+$p[2])/2)) ([int](($p[1]+$p[3])/2)); Start-Sleep -Milliseconds 400 }
function ByText($value) { (Tree).SelectSingleNode("//node[@text='$value']") }
Tap (ByText '设置')
for($i=0;$i -lt 4;$i++){ $choose=ByText '选择应用'; if($choose){break}; $size=(Tree).SelectSingleNode('//node[@scrollable="true"]').bounds; $coords=[regex]::Matches($size,'\d+')|ForEach-Object {[int]$_.Value}; $x=[int]($coords[2]/2); & $adb -s $Serial shell input swipe $x ($coords[3]-100) $x ($coords[1]+250) 450 }
Tap $choose
Tap ((Tree).SelectSingleNode('//node[@class="android.widget.EditText"]'))
& $adb -s $Serial shell input text com.roottun.probe
$tree=Tree
$target=$tree.SelectSingleNode('//node[starts-with(@text,"com.roottun.probe · UID ")]'); if(!$target){throw 'Target probe not visible'}
$label=if($Policy -eq 'DIRECT'){'直连'}else{'跟随订阅'}
Tap ($target.SelectSingleNode("following::node[@text='$label'][1]"))
Tap (ByText '保存并重启')
Tap (ByText '状态')
