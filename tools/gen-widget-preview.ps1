<#
  ============================================================================
  生成「添加小组件」选择页用的静态预览图（appwidget-provider 的 android:previewImage）
  ============================================================================

  为什么需要它：
    组件的预览首选 `android:previewLayout`（Android 12+ 启动器直接渲染真实布局），
    但**不是所有厂商启动器都认**，只认静态图片的还不少，所以再补一份 PNG 兜底。

  为什么用脚本画而不是截图：
    截图会随真实数据变（还可能带出真实课名/教室/寝室号）；这里画的是**固定的虚构样例**，
    几何值直接照抄布局 XML，改了布局就改这里的常量重跑一遍。

  用法（仓库根目录）：
    powershell -ExecutionPolicy Bypass -File tools/gen-widget-preview.ps1

  产物（@3x，与目标真机 520dpi 相近）：
    widget_preview_next_class.png     489×585 px（163×195dp，2×2）
    widget_preview_today_classes.png  1026×585 px（342×195dp，4×2 今明两列）
    widget_preview_energy_card.png    1026×585 px（342×195dp，4×2 电量与校园卡）

  ⚠️ 尺寸是**真机实测**的：4×2 = 342×195dp、2×2 = 163×195dp
  （官方老公式说的 250×110dp 在那台启动器上是错的，会被放成 3 列）。

  单位口径：脚本里所有尺寸都写 dp，乘 $Scale 得到像素。字号用 Pixel 单位（不是 Point），
  因为 Android 的 textSize 就是"em 高度 = 该 px 数"。
#>

Add-Type -AssemblyName System.Drawing

$ErrorActionPreference = 'Stop'

# 每 dp 多少像素
$Scale = 3

# ── 颜色：与 res/values/colors.xml 里 App 默认主题（M3 baseline 浅色）一致 ──
$Bg      = [System.Drawing.ColorTranslator]::FromHtml('#F3EDF7')
$Text    = [System.Drawing.ColorTranslator]::FromHtml('#1C1B1F')
$Dim     = [System.Drawing.ColorTranslator]::FromHtml('#49454F')
$Hint    = [System.Drawing.ColorTranslator]::FromHtml('#8A49454F')   # widget_text_hint（dim 的 62%）
$Accent  = [System.Drawing.ColorTranslator]::FromHtml('#6750A4')
$Divider = [System.Drawing.ColorTranslator]::FromHtml('#CAC4D0')
# 课程色：照搬 App 课表格子的取色风格（中饱和）
$Bar1 = [System.Drawing.ColorTranslator]::FromHtml('#6750A4')
$Bar2 = [System.Drawing.ColorTranslator]::FromHtml('#3FA88F')
$Bar3 = [System.Drawing.ColorTranslator]::FromHtml('#B084CC')
$Bar4 = [System.Drawing.ColorTranslator]::FromHtml('#4C7FD6')

$Corner = 28   # 系统小组件圆角（Android 13+ 的 system_app_widget_background_radius 是 28dp）

function New-Font([float]$sizeDp, [bool]$bold) {
    $style = if ($bold) { [System.Drawing.FontStyle]::Bold } else { [System.Drawing.FontStyle]::Regular }
    $sizePx = [float]($sizeDp * $Scale)
    foreach ($name in @('Microsoft YaHei UI', 'Microsoft YaHei', 'SimHei', 'SimSun')) {
        try {
            $f = New-Object System.Drawing.Font($name, $sizePx, $style, [System.Drawing.GraphicsUnit]::Pixel)
            if ($f.Name -eq $name) { return $f }
            $f.Dispose()
        } catch { }
    }
    return New-Object System.Drawing.Font('Arial', $sizePx, $style, [System.Drawing.GraphicsUnit]::Pixel)
}

function New-RoundedPath([float]$x, [float]$y, [float]$w, [float]$h, [float]$r) {
    $d = $r * 2
    if ($d -gt $w) { $d = $w }
    if ($d -gt $h) { $d = $h }
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $p.AddArc($x, $y, $d, $d, 180, 90)
    $p.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
    $p.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90)
    $p.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
    $p.CloseFigure()
    return $p
}

function New-Brush($color) { return New-Object System.Drawing.SolidBrush($color) }

# 画一行文字：$yDp 是本行顶部，$rowHDp 用来竖向居中；返回文字宽度（dp）
function Draw-Text($g, [string]$text, [System.Drawing.Font]$font, [System.Drawing.Brush]$brush,
                    [float]$xDp, [float]$yDp, [float]$rowHDp, [string]$align = 'left', [float]$rightDp = 0) {
    $x = [float]($xDp * $Scale)
    $y = [float]($yDp * $Scale)
    $rowH = [float]($rowHDp * $Scale)
    $size = $g.MeasureString($text, $font)
    if ($align -eq 'right') { $x = [float]($rightDp * $Scale) - $size.Width }
    $ty = $y + ($rowH - $size.Height) / 2.0
    $g.DrawString($text, $font, $brush, $x, $ty)
    return $size.Width / $Scale
}

function New-Canvas([int]$wDp, [int]$hDp) {
    $bmp = New-Object System.Drawing.Bitmap([int]($wDp * $Scale), [int]($hDp * $Scale),
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $g.Clear([System.Drawing.Color]::Transparent)
    $path = New-RoundedPath 0 0 ($wDp * $Scale) ($hDp * $Scale) ($Corner * $Scale)
    $b = New-Brush $Bg
    $g.FillPath($b, $path)
    $b.Dispose(); $path.Dispose()
    return @{ Bitmap = $bmp; Graphics = $g }
}

function Draw-Bar($g, [float]$xDp, [float]$yDp, [float]$wDp, [float]$hDp, $color) {
    $b = New-Brush $color
    $p = New-RoundedPath ([float]($xDp * $Scale)) ([float]($yDp * $Scale)) `
        ([float]($wDp * $Scale)) ([float]($hDp * $Scale)) (2 * $Scale)
    $g.FillPath($b, $p)
    $p.Dispose(); $b.Dispose()
}

$root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $root 'app'))) { $root = $PSScriptRoot }
$drawable = Join-Path $root 'app/src/main/res/drawable'

$bText = New-Brush $Text
$bDim = New-Brush $Dim
$bHint = New-Brush $Hint

$pad = 14    # widget_pad

# ════════════════════════════════════════════════════════════════════════════
# 1) 「今明课表」4×2 = 342×195dp：左列今天、右列明天，每列两节
# ════════════════════════════════════════════════════════════════════════════
$w = 342; $h = 195
$c = New-Canvas $w $h
$g = $c.Graphics

$fWeek = New-Font 10.5 $false
$fColHead = New-Font 12 $true
$fFooter = New-Font 9.5 $false
$fName = New-Font 15 $true
$fSub = New-Font 12 $false

# 右上角「第 5 周」
Draw-Text $g '第 5 周' $fWeek $bDim 0 $pad 12 'right' ($w - $pad) | Out-Null

$colsTop = $pad + 12 + 2
$colW = ($w - 2 * $pad - 2 * 8 - 1) / 2.0
$leftX = $pad
$divX = $pad + $colW + 8
$rightX = $divX + 1 + 8
$colBottom = $h - $pad

# 两列之间的竖分隔线
$db = New-Brush $Divider
$g.FillRectangle($db, [float]($divX * $Scale), [float]($colsTop * $Scale),
    [float](1 * $Scale), [float](($colBottom - $colsTop) * $Scale))
$db.Dispose()

# 一列：列头 + footer + 两行课
function Draw-DayColumn($g, [float]$x, [string]$head, [string]$footer,
                        $courses, [float]$top, [float]$bottom, [float]$colW) {
    Draw-Text $g $head $fColHead $bDim $x $top 14 | Out-Null
    Draw-Text $g $footer $fFooter $bHint 0 $top 14 'right' ($x + $colW) | Out-Null

    $rowsTop = $top + 14 + 8
    $area = $bottom - $rowsTop
    $rowCount = $courses.Count
    $rowH = ($area - ($rowCount - 1)) / $rowCount

    $y = $rowsTop
    for ($i = 0; $i -lt $rowCount; $i++) {
        $c0 = $courses[$i]
        $contentH = 17.5 + 2 + 14
        $nameY = $y + ($rowH - $contentH) / 2.0
        Draw-Bar $g $x ($y + ($rowH - 26) / 2.0) 3 26 $c0.Bar
        Draw-Text $g $c0.Name $fName $bText ($x + 11) $nameY 17.5 | Out-Null
        Draw-Text $g $c0.Place $fSub $bHint ($x + 11) ($nameY + 19.5) 14 | Out-Null
        Draw-Text $g $c0.Range $fSub $bDim 0 ($nameY + 19.5) 14 'right' ($x + $colW) | Out-Null
        $y += $rowH
        if ($i -lt $rowCount - 1) {
            $db = New-Brush $Divider
            $g.FillRectangle($db, [float]($x * $Scale), [float]($y * $Scale),
                [float]($colW * $Scale), [float](1 * $Scale))
            $db.Dispose()
            $y += 1
        }
    }
}

$todayCourses = @(
    @{ Name = '大学物理';     Place = '南3 101'; Range = '10:00-11:35'; Bar = $Accent },
    @{ Name = '程序设计基础'; Place = '南1 302'; Range = '14:00-15:35'; Bar = $Bar2 }
)
$tomorrowCourses = @(
    @{ Name = '编译原理实验'; Place = '中2414';  Range = '08:00-09:40'; Bar = $Bar3 },
    @{ Name = '线性代数';     Place = '南2 206'; Range = '10:10-11:50'; Bar = $Bar4 }
)

Draw-DayColumn $g $leftX  '今天 9.29 周二' '剩余 2 节' $todayCourses    $colsTop $colBottom $colW
Draw-DayColumn $g $rightX '明天 9.30 周三' '共有 3 节' $tomorrowCourses $colsTop $colBottom $colW

$out1 = Join-Path $drawable 'widget_preview_today_classes.png'
$c.Bitmap.Save($out1, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $c.Bitmap.Dispose()
Write-Host "写入 $out1"

# ════════════════════════════════════════════════════════════════════════════
# 2) 「下一节课」2×2 = 163×195dp
#    形态：表头（今天 · 第 N 周）+ 右上角圆气泡 + 一节课大字（课名/时间段/地点）+ 一行预告。
#    与 res/layout/widget_next_class.xml 一一对应；字号取 res/values/dimens.xml 里的
#    widget_name_lg(20) / widget_time_lg(12.5) / widget_preview_line(11.5) / widget_bubble(24)。
# ════════════════════════════════════════════════════════════════════════════
$w = 163; $h = 195
$c = New-Canvas $w $h
$g = $c.Graphics

$fColHead = New-Font 12 $true
$fNameLg = New-Font 20 $true
$fTimeLg = New-Font 12.5 $false
$fPreview = New-Font 11.5 $false
$fBubble = New-Font 13 $true

# 表头行（22dp 高）：左边「今天 · 第 5 周」，右边圆气泡
$top = $pad
$rowH = 24
Draw-Text $g '今天 · 第 5 周' $fColHead $bText $pad $top $rowH | Out-Null

$bubbleSize = 24
$bx = $w - $pad - $bubbleSize
$bAccent = New-Brush $Accent
$g.FillEllipse($bAccent, [float]($bx * $Scale), [float]($top * $Scale),
    [float]($bubbleSize * $Scale), [float]($bubbleSize * $Scale))
$bAccent.Dispose()
$bOnAccent = New-Brush ([System.Drawing.ColorTranslator]::FromHtml('#FFFFFF'))
Draw-Text $g '3' $fBubble $bOnAccent ($bx + $bubbleSize / 2.0 - 3.5) $top $bubbleSize | Out-Null
$bOnAccent.Dispose()

# 信息块：竖向居中在表头与预告之间
$infoTop = $top + $rowH + 2
$previewH = 15
$infoBottom = $h - $pad - $previewH
$blockH = 22 + 2 + 15 + 2 + 15     # 课名 + 时间段 + 地点
$barH = 46
$blockTop = $infoTop + (($infoBottom - $infoTop) - $blockH) / 2.0

Draw-Bar $g $pad ($blockTop + 4) 3 $barH $Bar4
$nameX = $pad + 3 + 8
Draw-Text $g '大学物理' $fNameLg $bText $nameX $blockTop 22 | Out-Null
Draw-Text $g '10:00-11:35' $fTimeLg $bDim $nameX ($blockTop + 24) 15 | Out-Null
Draw-Text $g '南3 101' $fTimeLg $bHint $nameX ($blockTop + 41) 15 | Out-Null

# 预告（钉在底部）
Draw-Text $g '→ 14:00 程序设计基础' $fPreview $bHint $pad ($h - $pad - $previewH) $previewH | Out-Null

$out2 = Join-Path $drawable 'widget_preview_next_class.png'
$c.Bitmap.Save($out2, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $c.Bitmap.Dispose()
Write-Host "写入 $out2"

# ════════════════════════════════════════════════════════════════════════════
# 3) 「电量与校园卡」4×2 = 342×195dp：左右两半，每半两块
# ════════════════════════════════════════════════════════════════════════════
$w = 342; $h = 195
$c = New-Canvas $w $h
$g = $c.Graphics

$fSideTitle = New-Font 12 $true
$fLabel = New-Font 12 $true
$fValue = New-Font 28 $true
$fUnit = New-Font 11 $false
$fBlockHint = New-Font 10.5 $false

Draw-Text $g '22:30 更新' $fWeek $bHint 0 $pad 12 'right' ($w - $pad) | Out-Null

$areaTop = $pad + 12
$areaBottom = $h - $pad
$colW = ($w - 2 * $pad - 2 * 8 - 1) / 2.0
$leftX = $pad
$divX = $pad + $colW + 8
$rightX = $divX + 1 + 8

$db = New-Brush $Divider
$g.FillRectangle($db, [float]($divX * $Scale), [float]($areaTop * $Scale),
    [float](1 * $Scale), [float](($areaBottom - $areaTop) * $Scale))
$db.Dispose()

function Draw-BlockColumn($g, [float]$x, [string]$title, $blocks, [float]$top, [float]$bottom, [float]$colW) {
    Draw-Text $g $title $fSideTitle $bText $x $top 14 | Out-Null

    $blocksTop = $top + 14
    $area = $bottom - $blocksTop
    $rowH = ($area - 1) / 2.0
    $y = $blocksTop
    for ($i = 0; $i -lt 2; $i++) {
        $bl = $blocks[$i]
        $contentH = 16 + 2 + 33
        $labelY = $y + ($rowH - $contentH) / 2.0
        Draw-Bar $g $x $labelY 3 16 $Accent
        Draw-Text $g $bl.Label $fLabel $bText ($x + 9) $labelY 16 | Out-Null

        $valueY = $labelY + 18
        $vw = Draw-Text $g $bl.Value $fValue $bText $x $valueY 33
        Draw-Text $g $bl.Unit $fUnit $bDim ($x + $vw + 3) $valueY 33 | Out-Null
        Draw-Text $g $bl.Hint $fBlockHint $bHint 0 $valueY 33 'right' ($x + $colW) | Out-Null

        $y += $rowH
        if ($i -eq 0) {
            $db = New-Brush $Divider
            $g.FillRectangle($db, [float]($x * $Scale), [float]($y * $Scale),
                [float]($colW * $Scale), [float](1 * $Scale))
            $db.Dispose()
            $y += 1
        }
    }
}

$leftBlocks = @(
    @{ Label = '照明'; Value = '152.82'; Unit = '度'; Hint = '可用约 29 天' },
    @{ Label = '空调'; Value = '80.21';  Unit = '度'; Hint = '可用约 21 天' }
)
$rightBlocks = @(
    @{ Label = '余额'; Value = '36.50'; Unit = '元'; Hint = '今日 3.20' },
    @{ Label = '本周'; Value = '87.60'; Unit = '元'; Hint = '5 笔' }
)

Draw-BlockColumn $g $leftX  '宿舍电量 · 柳园20号楼101' $leftBlocks  $areaTop $areaBottom $colW
Draw-BlockColumn $g $rightX '校园卡'                   $rightBlocks $areaTop $areaBottom $colW

$out3 = Join-Path $drawable 'widget_preview_energy_card.png'
$c.Bitmap.Save($out3, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $c.Bitmap.Dispose()
Write-Host "写入 $out3"

$bText.Dispose(); $bDim.Dispose(); $bHint.Dispose()
