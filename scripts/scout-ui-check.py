#!/usr/bin/env python3
"""ADB checks for synthetic Scout device fixtures. No provider calls or vault writes."""
import subprocess,xml.etree.ElementTree as E,re,sys

def adb(*args):return subprocess.check_output(['adb',*args],text=True)
def dump():
 adb('shell','uiautomator','dump','/sdcard/scout-window.xml')
 return E.fromstring(adb('shell','cat','/sdcard/scout-window.xml'))
def texts(root):return [(n.get('text') or n.get('content-desc')) for n in root.iter('node') if n.get('text') or n.get('content-desc')]
def click_once(label):
 root=dump(); parents={c:p for p in root.iter() for c in p}; fallback=[]
 for node in root.iter('node'):
  if label not in [node.get('text'),node.get('content-desc')]:continue
  fallback.append(node.get('bounds',''))
  while node.get('clickable')!='true' and node in parents:node=parents[node]
  xy=list(map(int,re.findall(r'\d+',node.get('bounds',''))))
  if len(xy)==4:
   adb('shell','input','tap',str((xy[0]+xy[2])//2),str((xy[1]+xy[3])//2));return
 for bounds in fallback:
  xy=list(map(int,re.findall(r'\d+',bounds)))
  if len(xy)==4:
   adb('shell','input','tap',str((xy[0]+xy[2])//2),str((xy[1]+xy[3])//2));return
 raise AssertionError('Control absent: '+label)
def click(label):
 for attempt in range(3):
  try:return click_once(label)
  except AssertionError:
   if attempt==2:raise

if __name__=='__main__':
 if sys.argv[1]=='jump':
  assert 'SCOUT DEVICE FIXTURE' in texts(dump()),'Prepare synthetic Scout fixture first'
  adb('shell','input','swipe','650','700','650','1500','400')
  click('Jump to latest')
  assert 'Keep' in texts(dump()),'Jump did not reach the long answer end'
  print('PASS: long-answer Jump to latest')
 elif sys.argv[1]=='flow':
  for label in sys.argv[2:]:click(label)
 elif sys.argv[1]=='click':click(sys.argv[2])
 elif sys.argv[1]=='assert':
  found=texts(dump());assert sys.argv[2] in found,'Missing visible control: '+sys.argv[2]
  print('PASS:',sys.argv[2])
 elif sys.argv[1]=='list':print('\n'.join(texts(dump())))
