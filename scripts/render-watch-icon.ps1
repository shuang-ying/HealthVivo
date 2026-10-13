# Render the project's circle and polyline SVG brand asset to the PNG required by BlueOS.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
[xml]$source = Get-Content -LiteralPath (Join-Path $projectRoot 'watch\assets\icon.svg') -Raw
Add-Type -AssemblyName System.Drawing
$width = [int]$source.DocumentElement.GetAttribute('width')
$height = [int]$source.DocumentElement.GetAttribute('height')
$scale = 4
$canvas = [System.Drawing.Bitmap]::new($width * $scale, $height * $scale, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$graphics = [System.Drawing.Graphics]::FromImage($canvas)
$output = [System.Drawing.Bitmap]::new($width, $height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$renderer = [System.Drawing.Graphics]::FromImage($output)
try {
    $graphics.Clear([System.Drawing.Color]::Transparent)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.ScaleTransform($scale, $scale)
    foreach ($element in $source.DocumentElement.ChildNodes) {
        if ($element.LocalName -eq 'circle') {
            $radius = [single]$element.GetAttribute('r')
            $brush = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml($element.GetAttribute('fill')))
            try { $graphics.FillEllipse($brush, [single]$element.GetAttribute('cx') - $radius, [single]$element.GetAttribute('cy') - $radius, 2 * $radius, 2 * $radius) }
            finally { $brush.Dispose() }
        } elseif ($element.LocalName -eq 'polyline') {
            $pen = [System.Drawing.Pen]::new([System.Drawing.ColorTranslator]::FromHtml($element.GetAttribute('stroke')), [single]$element.GetAttribute('stroke-width'))
            $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
            $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
            $pen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
            [System.Drawing.PointF[]]$points = $element.GetAttribute('points').Split(' ') | ForEach-Object { $pair = $_.Split(','); [System.Drawing.PointF]::new([single]$pair[0], [single]$pair[1]) }
            try { $graphics.DrawLines($pen, $points) } finally { $pen.Dispose() }
        }
    }
    $renderer.Clear([System.Drawing.Color]::Transparent)
    $renderer.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $renderer.DrawImage($canvas, 0, 0, $width, $height)
    if ($output.GetPixel(0, 0).A -ne 0 -or $output.GetPixel(113, 113).A -ne 0) { throw 'Watch icon must have transparent corners.' }
    $output.Save((Join-Path $projectRoot 'watch\src\common\icon.png'), [System.Drawing.Imaging.ImageFormat]::Png)
} finally { $renderer.Dispose(); $output.Dispose(); $graphics.Dispose(); $canvas.Dispose() }
Write-Output 'Round watch icon rendered from SVG, with transparent corners.'
