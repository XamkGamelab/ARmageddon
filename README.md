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



### TRAIN

  -Contains the basic structure for training a model
  
  -*Copy* the contents in a local directory and train there!
  
  -**Do not add any training data here**



### TRAINED_MODELS

  -Store previously trained models here
  
  -Within their respective directories, add the data, runs, .yaml and the actual .pt models
