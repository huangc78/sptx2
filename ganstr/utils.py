import os
import numpy as np
from PIL import Image
from torch.utils.data import Dataset
import torchvision.transforms as T
import torch

def partition(l, n):
    for i in range(0, len(l), n):
        yield l[i:i + n]
        
def weights_init_normal(m):
    classname = m.__class__.__name__
    if classname.find("Conv") != -1:
        torch.nn.init.normal_(m.weight.data, 0.0, 0.02)
    elif classname.find("BatchNorm") != -1:
        torch.nn.init.normal_(m.weight.data, 1.0, 0.02)
        torch.nn.init.constant_(m.bias.data, 0.0)
        
class SingleCellImageDataset(Dataset):
    def __init__(self, image_data, trns_data, sbtps_data, image_fileext, image_size, image_mean, image_std):
        'Initialization'
        
        self.image_data = image_data
        self.trns_data = trns_data
        self.sbtps_data = sbtps_data
        self.image_fileext = image_fileext
        self.image_mean = image_mean 
        self.image_std = image_std
        
        transformObjectList = [
            T.ToTensor(),
            T.CenterCrop(image_size),
            T.Resize(image_size, antialias=True),
            T.Normalize(image_mean, image_std),     
            T.RandomRotation(degrees=(0, 90)),
            T.RandomHorizontalFlip(),
            T.RandomVerticalFlip(),
            ]

        self.transform = T.Compose(transformObjectList)


    def __len__(self):
        'Denotes the total number of samples'
        return len(self.image_data)
    
    def __getitem__(self, index):
        'Generates one sample of data'
        # Select sample
        
        uuid = self.image_data.iloc[index].name
        imageFile = os.path.join(self.image_data.iloc[index]['image_filepath'], uuid+self.image_fileext)

        img = Image.open(imageFile)
        img = self.transform(img)
        # trns = None
        # sbtps = None
        
        if self.trns_data is not None:    
            trns = self.trns_data[index]
            sbtps = self.sbtps_data[index]
        
            return_values = (img, uuid, trns, sbtps)
        else:
            return_values = (img, uuid)
            
        
        return return_values
        
    
class SpotCellImageDataset(Dataset):
    def __init__(self, image_data, trns_data, sbtps_data, image_fileext, image_size, image_mean, image_std):
        'Initialization'
        
        self.image_data = image_data
        self.trns_data = trns_data
        self.sbtps_data = sbtps_data
        self.image_fileext = image_fileext
        self.image_mean = image_mean 
        self.image_std = image_std
        # self.trns_count_per_cell = trns_count_per_cell
        
        transformObjectList = [
            T.ToTensor(),
            T.CenterCrop(image_size),
            T.Resize(image_size, antialias=True),
            T.Normalize(image_mean, image_std),     
            T.RandomRotation(degrees=(0, 90)),
            T.RandomHorizontalFlip(),
            T.RandomVerticalFlip(),
            ]

        self.transform = T.Compose(transformObjectList)


    def __len__(self):
        'Denotes the total number of samples'
        return len(self.image_data)
    
    def __getitem__(self, index):
        'Generates one sample of data'
        # Select sample
        
        uuid = self.image_data.iloc[index].name
        imageFile = os.path.join(self.image_data.iloc[index]['image_filepath'], uuid+self.image_fileext)

        img = Image.open(imageFile)
        img = self.transform(img)
        
        if self.trns_data is not None:    
            # trns = self.trns_data.iloc[index].to_numpy()
            # trns = np.expm1(trns)/self.trns_count_per_cell
            # sbtps = self.sbtps_data.iloc[index].to_numpy()[0]
            trns = self.trns_data[index]
            sbtps = self.sbtps_data[index]
            
        return_values = (img, uuid, trns, sbtps) if self.trns_data is not None else (img, uuid)
        
        return return_values
        