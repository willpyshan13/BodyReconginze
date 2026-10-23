# -*- coding: utf-8 -*-
import subprocess
from pathlib import Path
folder = Path(__file__).resolve().parent.parent / 'demo-android/src/main/assets/coach_voice'
folder.mkdir(parents=True, exist_ok=True)
phrases = {
 'ready': '准备开始，请全身入镜。',
 'start': '第一组开始。请先站直准备。',
 'next': '休息结束。下一组开始。',
 'rest': '这一组完成了。请休息，调整呼吸。',
 'finish': '本次测试完成。请查看训练总结。',
 'stopped': '本次测试已结束，完成情况已保存。',
 'resume': '继续训练，请保持全身入镜。',
 'tracking': '关键点不清楚，请让髋部、膝盖和脚踝留在画面内。',
 'range': '本次未达到测试计数幅度，暂未计数。请参考示范，保持舒适。',
}
for i in range(1, 11): phrases['rep_' + str(i)] = '完成' + str(i) + '次。'
for key, text in phrases.items():
 subprocess.run(['say','-v','Tingting','-o',str(folder / (key+'.wav')),'--file-format=WAVE','--data-format=LEI16@22050','--channels=1',text],check=True)
print('Generated',len(phrases),'offline Chinese coach prompts')
