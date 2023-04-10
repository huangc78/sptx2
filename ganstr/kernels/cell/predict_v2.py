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
    param_json_data = model['parameters']
    
    # Prepare image data
    image_filepath_list = []
    image_uuid_list = []
    existing_image_fileext = None
    
    image_filename_list = glob.glob(os.path.join(opt.image_folder, '*','*'))
    
    for image_filename in image_filename_list:
        image_filepath, image_filename = os.path.split(image_filename)
        image_uuid, image_fileext = os.path.splitext(image_filename)
    
        assert existing_image_fileext == None or existing_image_fileext == image_fileext
        existing_image_fileext = image_fileext
    
        image_filepath_list.append(image_filepath)
        image_uuid_list.append(image_uuid)
    
    image_data = pd.DataFrame({'image_filepath': image_filepath_list, 'image_uuid':image_uuid_list})
    image_data.set_index('image_uuid', inplace=True)
    
    # Curate image and transcript data by cell UUID
    test_uuid_list = [value for value in image_data.index]
    
    # Create image data according to training, validation and test uuids.
    test_image_data = image_data.reindex(test_uuid_list)
    
    # Create datasets
    test_transformed_dataset = SingleCellImageDataset(test_image_data, None, None, image_fileext=existing_image_fileext, image_size=param_json_data['parameters']['image_size'], image_mean=param_json_data['image_mean'], image_std=param_json_data['image_std'], trns_count_per_cell=param_json_data['trns_count_per_cell'])
    
    # Create dataloaders
    testDataLoader = DataLoader(test_transformed_dataset, param_json_data['parameters']['batch_size'], shuffle=True, num_workers=4, pin_memory=True)
      
    # Initialize generator and discriminator
    if param_json_data['parameters']['model'] == 'ae':
        generator = ae.Generator(gene_num=param_json_data['gene_num'], latent_dim=param_json_data['parameters']['latent_dim'])
        classifier = ae.Classifier(gene_num=param_json_data['gene_num'], subtype_num=param_json_data['subtype_num'])
    elif param_json_data['parameters']['model'] == 'vae':
        generator = vae.Generator(gene_num=param_json_data['gene_num'], latent_dim=param_json_data['parameters']['latent_dim'])
        classifier = vae.Classifier(gene_num=param_json_data['gene_num'], subtype_num=param_json_data['subtype_num'])
    elif param_json_data['parameters']['model'] == 'vqvae':
        generator = vqvae.Generator(gene_num=param_json_data['gene_num'], latent_dim=param_json_data['parameters']['latent_dim'])
        classifier = vqvae.Classifier(gene_num=param_json_data['gene_num'], subtype_num=param_json_data['subtype_num'])
    elif param_json_data['parameters']['model'] == 'resvqvae':
        generator = resvqvae.Generator(gene_num=param_json_data['tgt_gene_num'], latent_dim=param_json_data['parameters']['latent_dim'])
        classifier = resvqvae.Classifier(gene_num=param_json_data['tgt_gene_num'], subtype_num=param_json_data['subtype_num'])
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
        csvWriter.writerow(['Object ID','subtype']+param_json_data['geneIDs'])
    
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
            gen_trns *= param_json_data['trns_count_per_cell']
            gen_trns = np.log1p(gen_trns)
            
            for j in range(batch_size):
                row = [uuid[j], pred[j].astype(np.int32)]+gen_trns[j].tolist()
                csvWriter.writerow(row)
                
        # spearmanMean = np.concatenate(spearmanAryList).mean(axis=0)
        # spearmanStddev = np.concatenate(spearmanAryList).std(axis=0)
        # print('Done! Correlation Mean: {:.3f}, Stddev: {:.3f}, p-value Mean: {:.3f}, Stddev: {:.3f}'.format(spearmanMean[0], spearmanStddev[0], spearmanMean[1], spearmanStddev[1]))
