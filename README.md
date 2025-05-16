>The idea here is to store previously trained models and provide the basic structure for training and testing environments.

>Your local test/train directories can remain messy, but when you add your trained models and data here, make sure it remains organised.


## Directories:


### IMAGESETS

  -Contains full imagesets with labels
  
  -(not split into val-train)



### RESULTS

  -Interesting results of previous test runs
  
  -You can add images of detections both good and bad, graphs, etc.



### TEST

  -Contains the basic structure you need for testing/running a model

  -testvideos folder contains videos that can be used to test the models

  -If you want to test on an imageset, you can copy images from TRAINED_MODELS/[modelname]/data/ or IMAGESETS
  
  -*Copy* the contents in a local directory and run testing there!

  -yolo_detect.py is the script for running a test. For an example command, check runModelCommand.txt


### TRAIN

  -Contains the basic structure for training a model
  
  -*Copy* the contents in a local directory and train there!
  
  -**Do not add any training data here**

  -train_val_split script splits your training data into train and validation folders

  -trainCommand.txt has an example command to train a model

  Your TRAIN folder should contain:
  
  >a "data" directory with "train" and "validation" inside

  >data.yaml file (set it up according to your data)

  >a model to fine-tune or if starting from scratch, the command should automatically download a model from ultralytics github

  >don't worry about cross_validate.py, you don't need it


### TRAINED_MODELS

  -Store previously trained models here
  
  -Within their respective directories, add the data, runs, .yaml and the actual .pt models
