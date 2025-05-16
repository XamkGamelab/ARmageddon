import os
import shutil
import yaml
from glob import glob
from sklearn.model_selection import KFold
from ultralytics import YOLO
import multiprocessing

def main():

    # Configuration
    DATA_DIR = 'set2_combined'           # Base dataset folder
    IMAGE_DIR = os.path.join(DATA_DIR, 'images')
    LABEL_DIR = os.path.join(DATA_DIR, 'labels')
    OUT_DIR = 'cv_splits'          # Output splits folder
    FOLDS = 5
    SEED = 42
    MODEL_WEIGHTS = '300aug2.pt'   # Base pretrained weights
    EPOCHS = 50
    IMG_SIZE = 480
    HSV_H = 0.25
    HSV_S = 0.6
    HSV_V = 0.5
    SCALE = 0.4
    DEGREES = 180

    NC = 8
    NAMES = ["3.5mm_audio", "DisplayPort", "Ethernet", "HDMI",
             "IEC-Power", "Micro-USB", "USB", "USB-C"]
    DATA_DIR_ABS = os.path.abspath(DATA_DIR)


    # Create output directory
    os.makedirs(OUT_DIR, exist_ok=True)

    # Gather image paths
    images = glob(os.path.join(IMAGE_DIR, '*.jpg')) + glob(os.path.join(IMAGE_DIR, '*.png'))
    images = sorted(images)
    print(f'Found {len(images)} images')  # sanity check

    # KFold setup
    kf = KFold(n_splits=FOLDS, shuffle=True, random_state=SEED)

    for fold, (train_idx, val_idx) in enumerate(kf.split(images), start=1):
        fold_dir = os.path.join(OUT_DIR, f'fold{fold}')
        # Clean or create
        if os.path.exists(fold_dir):
            shutil.rmtree(fold_dir)
        os.makedirs(fold_dir)
    
        # Prepare file lists
        train_files = [images[i] for i in train_idx]
        val_files = [images[i] for i in val_idx]

        train_txt = os.path.join(fold_dir, 'train.txt')
        val_txt   = os.path.join(fold_dir, 'val.txt')
    
        # 2. Write the training list
        with open(train_txt, 'w') as f:
            for img_path in train_files:
                f.write(f"{img_path}\n")
            
        # 3. Write the validation list
        with open(val_txt, 'w') as f:
            for img_path in val_files:
                f.write(f"{img_path}\n")
        # 4. Then point your data.yaml to those files:
        fold_yaml = {
        # absolute path so Ultralytics looks right here
        'path': os.path.abspath(fold_dir),
        'train': 'train.txt',
        'val':   'val.txt',
        'nc': NC,
        'names': NAMES
        }
        yaml_path = os.path.join(fold_dir, 'data.yaml')
        with open(os.path.join(fold_dir, 'data.yaml'), 'w') as f:
            yaml.dump(fold_yaml, f)

        # Train model on this fold
        model = YOLO(MODEL_WEIGHTS)
        model.train(
            data=yaml_path,
            epochs=EPOCHS,
            imgsz=IMG_SIZE,
            hsv_h=HSV_H,
            hsv_s=HSV_S,
            hsv_v=HSV_V,
            scale=SCALE,
            degrees=DEGREES,
            project=OUT_DIR,
            name=f'fold{fold}',
            exist_ok=True
        )

    print("Cross-validation training complete.")
    
if __name__ == "__main__":
    multiprocessing.freeze_support()   # safe‐guard for PyTorch multiprocessing on Windows
    main()
