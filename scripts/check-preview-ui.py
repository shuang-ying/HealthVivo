"""Exercise the real debug UI with demo data and a mocked models transport.
Stops when the phone leaves PreviewActivity. Never reads real API settings or saves records.
"""
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding='utf-8')
sys.stderr.reconfigure(encoding='utf-8')
ADB = ['D:/Android/Sdk/platform-tools/adb.exe', '-s', '10AC610LZC001DX']
def adb(*args):
    return subprocess.run(ADB + list(args), check=True, capture_output=True).stdout.decode('utf-8', errors='replace')
def guard():
    focused = [line for line in adb('shell', 'dumpsys', 'window').splitlines() if 'mFocusedApp=' in line]
    assert any('PreviewActivity' in line for line in focused), 'Phone left demo activity; no further input sent'
def ui():
    guard()
    adb('shell','uiautomator','dump','/sdcard/vitality-ui-check.xml')
    text = adb('shell','cat','/sdcard/vitality-ui-check.xml')
    adb('shell','rm','/sdcard/vitality-ui-check.xml')
    return ET.fromstring(text)
def find(label):
    tree = ui()
    for node in tree.iter('node'):
        if node.get('text') == label or node.get('content-desc') == label: return node
    raise AssertionError('UI label not visible: '+label)
def tap(label):
    node = find(label)
    x1,y1,x2,y2 = map(int,re.findall(r'\d+',node.get('bounds')))
    guard(); adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
def type_in(label, value, old_length=0):
    tap(label)
    time.sleep(.7)  # Allow the IME to attach before sending hardware input.
    if old_length:
        adb('shell','input','keyevent','123')
        adb('shell','input','keyevent',*(['67']*old_length))
    guard(); adb('shell','input','text',value)
    time.sleep(.4)
    adb('shell','input','keyevent','4')
def has(text): return any(text in node.get('text','') for node in ui().iter('node'))

if __name__ == '__main__':
    tap('AI 配置')
    type_in('服务地址', 'https://models.example.com/v1')
    type_in('API 密钥', 'demo-secret')
    time.sleep(1.3)
    assert has('检测到 2 个模型'), 'Automatic model discovery did not finish'
    tap('选择模型')
    tap('demo-vision')
    assert has('demo-vision'), 'Picked model did not populate model field'
    type_in('服务地址', 'https://unsupported.example.com/v1', len('https://models.example.com/v1'))
    type_in('API 密钥', 'demo-secret')
    time.sleep(1.3)
    assert has('此服务未开放模型列表'), 'Failure did not show manual fallback'
    type_in('模型名称', 'manual-model', len('demo-vision'))
    assert has('manual-model'), 'Manual fallback not editable'
    tap('取消')
    print(json.dumps(dict(automatic_detection=True, picker_populates_model=True, unsupported_provider_fallback=True,
                         manual_model_edit=True, demo_only=True, real_network_requests=0), ensure_ascii=False))
