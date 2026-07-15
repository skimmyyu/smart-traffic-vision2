@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo ========================================
echo  Activate YOLOv8 training environment
echo ========================================
call .venv\Scripts\activate.bat
python -c "import torch; print('torch', torch.__version__, 'cuda', torch.cuda.is_available()); print(torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'CPU only')"
cmd /k
