import glob
import pandas as pd
import os
# from PIL import Image
# import argparse
import numpy as np
import json
import csv
import torch
from torch.autograd import Variable
from torch.utils.data import DataLoader
from models import ae, vae, vqvae, resvqvae
from utils import SingleCellImageDataset, weights_init_normal
from tqdm import tqdm

def predict(opt):
    cuda = True if torch.cuda.is_available() else False
    
    model = torch.load(opt.model_file)
    paramJSONData = model['parameters']
    
    # Prepare image data
    imageFilePathList = []
    imageFileUuidList = []
    existingImageFileExt = None
    
    imageFileNameList = glob.glob(os.path.join(opt.image_folder, '*','*'))
    
    for imageFileName in imageFileNameList:
        imageFilePath, imageFileName = os.path.split(imageFileName)
        imageFileUuid, imageFileExt = os.path.splitext(imageFileName)
    
        assert existingImageFileExt == None or existingImageFileExt == imageFileExt
        existingImageFileExt = imageFileExt
    
        imageFilePathList.append(imageFilePath)
        imageFileUuidList.append(imageFileUuid)
    
    imageData = pd.DataFrame({'imageFilePath': imageFilePathList, 'imageFileUuid':imageFileUuidList})
    imageData.set_index('imageFileUuid', inplace=True)
    
    # Curate image and transcript data by cell UUID
    testUuidList = [value for value in imageData.index]
    
    # Create image data according to training, validation and test uuids.
    testImageData = imageData.reindex(testUuidList)
    
    # Create datasets
    testTransformedDataset = SingleCellImageDataset(testImageData, None, None, imageFileExt=existingImageFileExt, imageSize=paramJSONData['parameters']['image_size'], imageMean=paramJSONData['imageMean'], imageStd=paramJSONData['imageStd'], trnsCountPerCell=paramJSONData['trnsCountPerCell'])
    
    # Create dataloaders
    testDataLoader = DataLoader(testTransformedDataset, paramJSONData['parameters']['batch_size'], shuffle=True, num_workers=4, pin_memory=True)
      
    # Initialize generator and discriminator
    if paramJSONData['parameters']['model'] == 'ae':
        generator = ae.Generator(geneNum=paramJSONData['geneNum'], latentDim=paramJSONData['parameters']['latent_dim'])
        classifier = ae.Classifier(geneNum=paramJSONData['geneNum'], subtypeNum=paramJSONData['subtypeNum'])
    elif paramJSONData['parameters']['model'] == 'vae':
        generator = vae.Generator(geneNum=paramJSONData['geneNum'], latentDim=paramJSONData['parameters']['latent_dim'])
        classifier = vae.Classifier(geneNum=paramJSONData['geneNum'], subtypeNum=paramJSONData['subtypeNum'])
    elif paramJSONData['parameters']['model'] == 'vqvae':
        generator = vqvae.Generator(geneNum=paramJSONData['geneNum'], latentDim=paramJSONData['parameters']['latent_dim'])
        classifier = vqvae.Classifier(geneNum=paramJSONData['geneNum'], subtypeNum=paramJSONData['subtypeNum'])
    elif paramJSONData['parameters']['model'] == 'resvqvae':
        generator = resvqvae.Generator(geneNum=paramJSONData['geneNum'], latentDim=paramJSONData['parameters']['latent_dim'])
        classifier = resvqvae.Classifier(geneNum=paramJSONData['geneNum'], subtypeNum=paramJSONData['subtypeNum'])
    else:
        raise Exception("The chosen model is not supported")
    
    generator.load_state_dict(model['generator_model_state_dict'])
    classifier.load_state_dict(model['classifier_model_state_dict'])
    
    generator.eval()
    classifier.eval()
    
    if cuda:
        generator.cuda()
        classifier.cuda()
    
    # Tensor types
    FloatTensor = torch.cuda.FloatTensor if cuda else torch.FloatTensor
    LongTensor = torch.cuda.LongTensor if cuda else torch.LongTensor
    
    with open(opt.output_file, 'w', newline='') as f:
        csvWriter = csv.writer(f)
        csvWriter.writerow(['Object ID','subtype']+paramJSONData['geneIDs'])
    
        for i, (imgs, uuid,) in enumerate(tqdm(testDataLoader)):
            batch_size = imgs.shape[0]
            
            # Configure input
            real_imgs = Variable(imgs.type(FloatTensor))
            
            # Generate a batch of images
            gen_trns = generator(real_imgs)
            
            label = classifier(gen_trns)
            pred = np.argmax(label.data.cpu().numpy(), axis=1)
        
            # Compute Spearmans Corr
            # spearmanAry = np.asarray([spearmanr(gen_trns.detach().cpu().numpy()[j], trns.detach().cpu().numpy()[j]) for j in range(batch_size)])
            # spearmanAryList.append(spearmanAry)
    
            # Ready to save
            gen_trns = gen_trns.data.cpu().numpy()
            gen_trns *= paramJSONData['trnsCountPerCell']
            gen_trns = np.log1p(gen_trns)
            
            for j in range(batch_size):
                row = [uuid[j], pred[j].astype(np.int32)]+gen_trns[j].tolist()
                csvWriter.writerow(row)
                
        # spearmanMean = np.concatenate(spearmanAryList).mean(axis=0)
        # spearmanStddev = np.concatenate(spearmanAryList).std(axis=0)
        # print('Done! Correlation Mean: {:.3f}, Stddev: {:.3f}, p-value Mean: {:.3f}, Stddev: {:.3f}'.format(spearmanMean[0], spearmanStddev[0], spearmanMean[1], spearmanStddev[1]))
