import glob
import pandas as pd
import os
from PIL import Image
import numpy as np
from random import sample
import csv
from scipy.stats import spearmanr
import torch
from torch.autograd import Variable
from torch.utils.data import DataLoader
from torch.utils.tensorboard import SummaryWriter
from models import ae, vae, vqvae, resvqvae
from loss import PearsonCorrLoss
from utils import SingleCellImageDataset, weights_init_normal, partition
from tqdm import tqdm
from torchviz import make_dot
import warnings
from tensorboard import program
import webbrowser
import scanpy as sc
import json
import anndata as ad
from scipy.sparse import csr_matrix

# GPU environment by CUDA
cuda = True if torch.cuda.is_available() else False

# Tensor types
FloatTensor = torch.cuda.FloatTensor if cuda else torch.FloatTensor
LongTensor = torch.cuda.LongTensor if cuda else torch.LongTensor 

def train(opt):
    if opt.model_prefix != "": opt.model_prefix += "-"
    
    # Tensorboard
    if opt.tensorboard:
        tb = program.TensorBoard()
        tb.configure(argv=[None, '--logdir', os.path.join(opt.output_folder, "runs"), '--port', opt.tensorboard_port])
        url = tb.launch()
        print(f"Tensorboard listening on {url}")
        webbrowser.open(url)
        tensorboard_writer = SummaryWriter(os.path.join(opt.output_folder, "runs"))
    
    # Loss functions
    adversarial_loss = torch.nn.BCELoss()
    auxiliary_loss = torch.nn.CrossEntropyLoss()
    reconstruction_loss = torch.nn.MSELoss() 
    cell_correlation_loss = PearsonCorrLoss(dim=1)
    gene_correlation_loss = PearsonCorrLoss(dim=0)
    classification_loss = torch.nn.CrossEntropyLoss()
    
    # Prepare transcript data
    trns_anndata = sc.read_h5ad(opt.transcript_file)
    trns_data = trns_anndata.to_df()
    trns_data.dropna(axis=1, how='all', inplace=True) # Remove columns of all NaN
    trns_data.dropna(inplace=True) # Drop rows containing NaN
    
    trns_data = np.expm1(trns_data)
    trns_count_per_cell = trns_data.sample(100).sum(axis=1).mean()
    assert (trns_data.sample(100).sum(axis=1)/trns_count_per_cell).std() < 1.0, "transcript data is not normalized"
    trns_data = trns_data.drop(trns_data[trns_data.sum(axis=1)==0].index)
    # trns_data = np.log1p(trns_data)
    # trns_log1p_minmaxscale = trns_data.max().max()
    trns_data = trns_data.div(trns_data.sum(axis=1), axis=0)
    
    gene_num = len(trns_data.columns)
    sbtps_data = trns_anndata.obs.subtype.copy()
    subtype_num = len(trns_anndata.obs.subtype.copy().unique())
    
    # Prepare image data
    image_filepath_list = []
    image_uuid_list = []
    existing_image_fileext = None
    image_filename_list = glob.glob(os.path.join(opt.image_folder, '*','*'))
    
    # Normalization 
    sampled_image_filename_list = sample(image_filename_list,100)
    sampled_image_arraylist = []
    
    for sifn in sampled_image_filename_list:
        imgBuf = Image.open(sifn)
        imgAry = np.array(imgBuf).astype(np.float32)
        sampled_image_arraylist.append(imgAry.reshape((1, *imgAry.shape)))
    
    sampled_image_arraylist_concat = np.concatenate(sampled_image_arraylist)
    image_mean = sampled_image_arraylist_concat.mean(axis=(0,1,2))
    image_std = (sampled_image_arraylist_concat-image_mean).std(axis=(0,1,2))
    
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
    total_uuid_list = [value for value in image_data.index if value in trns_data.index if value in sbtps_data.index]
    
    # Split all data into training, validation and test uuids.
    training_uuid_list = total_uuid_list[:int(len(total_uuid_list)*0.9)]
    validation_uuid_list = total_uuid_list[int(len(total_uuid_list)*0.9):]
    
    # Create image data according to training, validation and test uuids.
    training_image_data = image_data.reindex(training_uuid_list)
    validation_image_data = image_data.reindex(validation_uuid_list)
    
    # Create transcript data according to training, validation and test uuids.
    training_trns_data = trns_data.reindex(training_uuid_list).to_numpy()
    validation_trns_data = trns_data.reindex(validation_uuid_list).to_numpy()
        
    # Create sbtps data according to training, validation and test uuids.
    training_subtype_data = sbtps_data.reindex(training_uuid_list).astype(np.int32).to_numpy()
    validation_subtype_data = sbtps_data.reindex(validation_uuid_list).astype(np.int32).to_numpy()

    # Create datasets
    training_transformed_dataset = SingleCellImageDataset(
        training_image_data, 
        training_trns_data, 
        training_subtype_data, 
        image_fileext=existing_image_fileext, 
        image_size=opt.image_size, 
        image_mean=image_mean, 
        image_std=image_std) 
    
    validation_transformed_dataset = SingleCellImageDataset(
        validation_image_data, 
        validation_trns_data, 
        validation_subtype_data,  
        image_fileext=existing_image_fileext, 
        image_size=opt.image_size, 
        image_mean=image_mean, 
        image_std=image_std) 
    
    # Create dataloaders
    trainingDataLoader = DataLoader(training_transformed_dataset, opt.batch_size, shuffle=True, num_workers=4, pin_memory=True)
    validationDataLoader = DataLoader(validation_transformed_dataset, opt.batch_size, shuffle=True, num_workers=4, pin_memory=True)
      
    # Initialize generator and discriminator      
    if opt.model == 'ae':
        generator = ae.Generator(gene_num=gene_num, latent_dim=opt.latent_dim)
        discriminator = ae.Discriminator(gene_num=gene_num, subtype_num=subtype_num)
    elif opt.model == 'vae':
        generator = vae.Generator(gene_num=gene_num, latent_dim=opt.latent_dim)
        discriminator = vae.Discriminator(gene_num=gene_num, subtype_num=subtype_num)
    elif opt.model == 'vqvae':
        generator = vqvae.Generator(gene_num=gene_num, latent_dim=opt.latent_dim)
        discriminator = vqvae.Discriminator(gene_num=gene_num, subtype_num=subtype_num)
    elif opt.model == 'resvqvae':
        generator = resvqvae.Generator(gene_num=gene_num, latent_dim=opt.latent_dim)
        discriminator = resvqvae.Discriminator(gene_num=gene_num, subtype_num=subtype_num)
    else:
        raise Exception("The chosen model is not supported")
            
    if cuda:
        generator.cuda()
        discriminator.cuda()
        adversarial_loss.cuda()
        auxiliary_loss.cuda()
        reconstruction_loss.cuda()
        cell_correlation_loss.cuda()
        gene_correlation_loss.cuda()
        classification_loss.cuda()
    
    # Initialize weights
    generator.apply(weights_init_normal)
    discriminator.apply(weights_init_normal)
    
    # Optimizers
    optimizer_G = torch.optim.Adam(generator.parameters(), lr=opt.learning_rate, betas=(opt.beta_1, opt.beta_2))
    optimizer_D = torch.optim.Adam(discriminator.parameters(), lr=opt.learning_rate, betas=(opt.beta_1, opt.beta_2))
    
    if opt.generate_graphs:
        imgs, trns, sbtps, uuid = next(iter(trainingDataLoader))
        real_imgs = Variable(imgs.type(FloatTensor))
        gen_trns = generator(real_imgs)
        validity, aux, cls = discriminator(gen_trns)
        
        make_dot(gen_trns, params=dict(list(generator.named_parameters()))).render(filename=os.path.join(opt.output_folder, opt.model_prefix+"generator"), format="png")
        make_dot((validity, aux, cls), params=dict(list(discriminator.named_parameters()))).render(os.path.join(opt.output_folder, opt.model_prefix+"discriminator"), format="png")
        
    if opt.tensorboard:
        class Wrapper(torch.nn.Module):
            def __init__(self, 
                         generator, 
                         discriminator, 
                         ):
                super(Wrapper, self).__init__()
                self.generator = generator
                self.discriminator = discriminator
    
            def forward(self, imgs):
                real_imgs = Variable(imgs.type(FloatTensor))
                gen_trns = self.generator(real_imgs)
                validity, aux, cls = self.discriminator(gen_trns)
            
                return validity, aux, cls
        
        wrapper = Wrapper(
            generator, 
            discriminator, 
            )
        imgs, _, _, _ = next(iter(trainingDataLoader))
        real_imgs = Variable(imgs.type(FloatTensor))
        tensorboard_writer.add_graph(wrapper, real_imgs)

    param_json_data = {
                "parameters": vars(opt),
                "gene_id": trns_data.columns.tolist(),
                # "trns_count_per_cell": trns_count_per_cell,
                "gene_num": gene_num,
                "subtype_num": subtype_num,
                "image_std": image_std.tolist(),
                "image_mean": image_mean.tolist(),
                }
             
    # ----------
    #  Training
    # ----------
    
    last_gen_cls_acc = 0
    
    optimizationRange = range(opt.n_epochs) if opt.generate_progress else tqdm(range(opt.n_epochs), total=opt.n_epochs)
    for epoch in optimizationRange:
        # -----------------
        # Training Loop
        # -----------------
        generator.train()
        discriminator.train()
    
        for i, (imgs, uuid, trns, sbtps) in enumerate(trainingDataLoader):
    
            batch_size = imgs.shape[0]
    
            # Adversarial ground truths
            valid = Variable(FloatTensor(batch_size, 1).fill_(1.0), requires_grad=False)
            fake = Variable(FloatTensor(batch_size, 1).fill_(0.0), requires_grad=False)
            fake_aux_gt = Variable(LongTensor(batch_size).fill_(subtype_num), requires_grad=False)
    
            # Configure input
            real_imgs = Variable(imgs.type(FloatTensor))
            real_trns = Variable(trns.type(FloatTensor))
            real_sbtps = Variable(sbtps.type(LongTensor))
    
            # -----------------
            #  Train Generator
            # -----------------
            optimizer_G.zero_grad()
    
            # Generate a batch of images
            gen_trns = generator(real_imgs)
            
            # Loss measures generator's ability to fool the discriminator
            
            validity, _, _ = discriminator(gen_trns)
            
            adv_loss = adversarial_loss(validity, valid)
            
            recon_loss = reconstruction_loss(gen_trns, real_trns)
            
            
            if opt.model == 'vae': kl_loss = generator.kl
            elif opt.model == 'vqvae' or opt.model == 'resvqvae': vq_loss = generator.vq_loss
                
            cell_corr_loss = cell_correlation_loss(gen_trns, real_trns)
            
            gene_corr_loss = gene_correlation_loss(gen_trns, real_trns)
            
            adv_loss_weighted = opt.adversarial_weight*adv_loss
            recon_loss_weighted = opt.reconstruction_weight*recon_loss
            
            if opt.model == 'vae': kl_loss_weighted = opt.kl_divergence_weight*kl_loss
            elif opt.model == 'vqvae' or opt.model == 'resvqvae': vq_loss_weighted = opt.vq_loss_weight*vq_loss
                
            cell_corr_loss_weighted = opt.cell_correlation_weight*cell_corr_loss
            gene_corr_loss_weighted = opt.gene_correlation_weight*gene_corr_loss
            
            if opt.model == 'vae': g_loss_weighted = (adv_loss_weighted + recon_loss_weighted + kl_loss_weighted + cell_corr_loss_weighted + gene_corr_loss_weighted) / 5
            elif opt.model == 'vqvae' or opt.model == 'resvqvae': g_loss_weighted = (adv_loss_weighted + recon_loss_weighted + vq_loss_weighted + cell_corr_loss_weighted + gene_corr_loss_weighted) / 5
            else: g_loss_weighted = (adv_loss_weighted + recon_loss_weighted + cell_corr_loss_weighted + gene_corr_loss_weighted) / 4
    
            g_loss_weighted.backward()
            optimizer_G.step()
    
            # ---------------------
            #  Train Discriminator
            # ---------------------
            optimizer_D.zero_grad()
    
            # Loss for real images
            real_pred, real_aux, real_cls = discriminator(real_trns)
            d_real_loss = (adversarial_loss(real_pred, valid) + auxiliary_loss(real_aux, real_sbtps)) / 2
            
            # Loss for fake images
            
            fake_pred, fake_aux, gen_cls = discriminator(gen_trns.detach())
            
                
            
            d_fake_loss = (adversarial_loss(fake_pred, fake) + auxiliary_loss(fake_aux, fake_aux_gt)) / 2

            # Loss for classification
            real_cls_loss = classification_loss(real_cls, real_sbtps)
            gen_cls_loss = classification_loss(gen_cls, real_sbtps)
                
            # Total discriminator loss
            disc_loss = (d_real_loss + d_fake_loss) / 2
            cls_loss = (gen_cls_loss + real_cls_loss) / 2
            disc_loss_weighted = (opt.discrimination_weight*disc_loss + opt.classification_weight*cls_loss) / 2
                        
            disc_loss_weighted.backward()
            optimizer_D.step()
    
            # Calculate discriminator accuracy
            pred = np.concatenate([real_aux.data.cpu().numpy(), fake_aux.data.cpu().numpy()], axis=0)
            gt = np.concatenate([real_sbtps.data.cpu().numpy(), fake_aux_gt.data.cpu().numpy()], axis=0)
            disc_acc = np.mean(np.argmax(pred, axis=1) == gt)
    
            real_cls_acc = np.mean(np.argmax(real_cls.data.cpu().numpy(), axis=1) == real_sbtps.data.cpu().numpy())
            gen_cls_acc = np.mean(np.argmax(gen_cls.data.cpu().numpy(), axis=1) == real_sbtps.data.cpu().numpy())
                   
            # ---------------------
            #  Training Finished
            # ---------------------
            with warnings.catch_warnings():
                warnings.simplefilter("ignore")
                # Calculate Spearman's correlation over cells
                cell_spearmanr_list = [spearmanr(gen_trns.detach().cpu().numpy()[i], trns.detach().cpu().numpy()[i]) for i in range(batch_size)]
                cell_corr, cell_pval = np.asarray(cell_spearmanr_list).mean(axis=0)
                
                gene_spearmanr_list = [spearmanr(gen_trns.detach().cpu().numpy()[:, i], trns.detach().cpu().numpy()[:, i]) for i in range(gene_num)]
                gene_spearmanr_ary = np.asarray(gene_spearmanr_list)
                
                gene_spearmanr_nan_idx = np.argwhere(np.isnan(gene_spearmanr_ary).any(axis=1)).reshape(-1).tolist()
                gene_spearmanr_ary = np.delete(gene_spearmanr_ary, gene_spearmanr_nan_idx, 0)
                
                gene_corr, gene_pval = gene_spearmanr_ary.mean(axis=0)
                
            if opt.generate_progress:
                print(
                    "Training "+
                    "[Epoch {:}/{:}] "+
                    "[Batch {}/{}] "+
                    "[D loss: {:.3f}, acc: {:.2f}%] "+
                    "[A loss: {:.3f}] "+
                    "[R loss: {:.3f}] "+
                    "[X loss: {:.3f}/{:.3f}] "+
                    "[C real loss: {:.3f}, real acc: {:.2f}%, gen loss: {:.3f}, fake gen: {:.2f}%] "+
                    "[Cell corr: {:.3f}/pval: {:.3f}]"+
                    "[Gene corr: {:.3f}/pval: {:.3f}]"
                    .format(epoch, 
                            opt.n_epochs, i, len(trainingDataLoader), 
                            disc_loss.item(), 100 * disc_acc.item(), 
                            adv_loss.item(),
                            recon_loss.item(), 
                            cell_corr_loss.item(), gene_corr_loss.item(), 
                            real_cls_loss.item(), 100 * real_cls_acc.item(), gen_cls_loss.item(), 100 * gen_cls_acc.item(),
                            cell_corr, 
                            cell_pval,
                            gene_corr, 
                            gene_pval)
                    )
            
            if opt.tensorboard:
                tensorboard_writer.add_scalar("training_loss/disc_loss", disc_loss.item(), epoch)
                tensorboard_writer.add_scalar("training_loss/adv_loss", adv_loss.item(), epoch)
                tensorboard_writer.add_scalar("training_loss/recon_real_loss", recon_loss.item(), epoch)
                if opt.model == 'vae': tensorboard_writer.add_scalar("training_loss/kl_loss", kl_loss.item(), epoch)
                elif opt.model == 'vqvae' or opt.model == 'resvqvae': tensorboard_writer.add_scalar("training_loss/vq_loss", vq_loss.item(), epoch)
                tensorboard_writer.add_scalar("training_loss/cell_corr_loss", cell_corr_loss.item(), epoch)
                tensorboard_writer.add_scalar("training_loss/gene_corr_loss", gene_corr_loss.item(), epoch)
                tensorboard_writer.add_scalar("training_loss/real_cls_loss", real_cls_loss.item(), epoch)
                tensorboard_writer.add_scalar("training_loss/gen_cls_loss", gen_cls_loss.item(), epoch)
                tensorboard_writer.add_scalar("training_accuracy/disc_acc", 100 * disc_acc.item(), epoch)
                tensorboard_writer.add_scalar("training_accuracy/real_cls_acc", 100 * real_cls_acc.item(), epoch)
                tensorboard_writer.add_scalar("training_accuracy/gen_cls_acc", 100 * gen_cls_acc.item(), epoch)
                tensorboard_writer.add_scalar("training_correlation/cell_corr", cell_corr, epoch)
                tensorboard_writer.add_scalar("training_correlation/cell_pval", cell_pval, epoch)
                tensorboard_writer.add_scalar("training_correlation/gene_corr", gene_corr, epoch)
                tensorboard_writer.add_scalar("training_correlation/gene_pval", gene_pval, epoch)
                    
                tensorboard_writer.flush()
    
        # -----------------
        # Validation Loop
        # -----------------
        generator.eval()
        discriminator.eval()
        
        val_disc_loss = 0
        val_disc_acc = 0
        val_adv_loss = 0
        val_recon_loss = 0
        if opt.model == 'vae': val_kl_loss = 0
        elif opt.model == 'vqvae' or opt.model == 'resvqvae': val_vq_loss = 0
        val_cell_corr_loss = 0
        val_gene_corr_loss = 0
        val_real_cls_loss = 0
        val_real_cls_acc = 0
        val_gen_cls_loss = 0
        val_gen_cls_acc = 0
        val_count = 0
        val_cell_corr = 0
        val_cell_pval = 0
        val_gene_corr = 0
        val_gene_pval = 0
            
        for i, (imgs, uuid, trns, sbtps) in enumerate(validationDataLoader):
    
            batch_size = imgs.shape[0]
    
            # Adversarial ground truths
            valid = Variable(FloatTensor(batch_size, 1).fill_(1.0), requires_grad=False)
            fake = Variable(FloatTensor(batch_size, 1).fill_(0.0), requires_grad=False)
            fake_aux_gt = Variable(LongTensor(batch_size).fill_(subtype_num), requires_grad=False)
    
            # Configure input
            real_imgs = Variable(imgs.type(FloatTensor))
            real_trns = Variable(trns.type(FloatTensor))
            real_sbtps = Variable(sbtps.type(LongTensor))
    
            # -----------------
            #  Validation Generator
            # -----------------
    
            # Generate a batch of images
            gen_trns = generator(real_imgs)
            
            
            # Loss measures generator's ability to fool the discriminator
            validity, _, _ = discriminator(gen_trns)
            
            adv_loss = adversarial_loss(validity, valid)
            
            
            recon_loss = reconstruction_loss(gen_trns, real_trns)
            
                
                
            
            if opt.model == 'vae': kl_loss = generator.kl
            elif opt.model == 'vqvae' or opt.model == 'resvqvae': vq_loss = generator.vq_loss
            
            
            
            
            
            
            cell_corr_loss = cell_correlation_loss(gen_trns, real_trns)
            
            gene_corr_loss = gene_correlation_loss(gen_trns, real_trns)
                
                
            # -------------------------
            #  Validation Discriminator
            # -------------------------
    
            # Loss for real images
            real_pred, real_aux, real_cls = discriminator(real_trns)
            d_real_loss = (adversarial_loss(real_pred, valid) + auxiliary_loss(real_aux, real_sbtps)) / 2
    
            # Loss for fake images
            fake_pred, fake_aux, gen_cls = discriminator(gen_trns.detach())
                
            d_fake_loss = (adversarial_loss(fake_pred, fake) + auxiliary_loss(fake_aux, fake_aux_gt)) / 2
    
            # Loss for classification
            real_cls_loss = classification_loss(real_cls, real_sbtps)
            gen_cls_loss = classification_loss(gen_cls, real_sbtps)
                
            # Total discriminator loss
            disc_loss = (d_real_loss + d_fake_loss) / 2
            cls_loss = (gen_cls_loss+real_cls_loss) / 2
    
            # Calculate discriminator accuracy
            pred = np.concatenate([real_aux.data.cpu().numpy(), fake_aux.data.cpu().numpy()], axis=0)
            gt = np.concatenate([real_sbtps.data.cpu().numpy(), fake_aux_gt.data.cpu().numpy()], axis=0)
            disc_acc = np.mean(np.argmax(pred, axis=1) == gt)
    
            # Calculate discriminator accuracy
            real_cls_acc = np.mean(np.argmax(real_cls.data.cpu().numpy(), axis=1) == real_sbtps.data.cpu().numpy())
            gen_cls_acc = np.mean(np.argmax(gen_cls.data.cpu().numpy(), axis=1) == real_sbtps.data.cpu().numpy())
            
            # -------------------------
            #  Training Finished
            # -------------------------
            with warnings.catch_warnings():
                warnings.simplefilter("ignore")
                # Calculate Spearman's correlation over cells
                cell_spearmanr_list = [spearmanr(gen_trns.detach().cpu().numpy()[i], trns.detach().cpu().numpy()[i]) for i in range(batch_size)]
                cell_corr, cell_pval = np.asarray(cell_spearmanr_list).mean(axis=0)
                
                gene_spearmanr_list = [spearmanr(gen_trns.detach().cpu().numpy()[:, i], trns.detach().cpu().numpy()[:, i]) for i in range(gene_num)]
                gene_spearmanr_ary = np.asarray(gene_spearmanr_list)
                
                gene_spearmanr_nan_idx = np.argwhere(np.isnan(gene_spearmanr_ary).any(axis=1)).reshape(-1).tolist()
                gene_spearmanr_ary = np.delete(gene_spearmanr_ary, gene_spearmanr_nan_idx, 0)
                
                gene_corr, gene_pval = gene_spearmanr_ary.mean(axis=0)
            
            if opt.generate_progress:
                print(
                    "Training "+
                    "[Epoch {}/{}] "+
                    "[Batch {}/{}] "+
                    "[D loss: {:.3f}, acc: {:.2f}%]"+
                    "[A loss: {:.3f}]"+
                    "[R loss: {:.3f}]"+
                    "[X loss: {:.3f}/{:.3f}]"+
                    "[C real loss: {:.3f}, real acc: {:.2f}%, gen loss: {:.3f}, gen acc: {:.2f}%]"+
                    "[Cell corr: {:.3f}/pval: {:.3f}]"+
                    "[Gene corr: {:.3f}/pval: {:.3f}]"
                    .format(epoch, 
                            opt.n_epochs, i, len(trainingDataLoader), 
                            disc_loss.item(), 100 * disc_acc.item(), 
                            adv_loss.item(),
                            recon_loss.item(), 
                            cell_corr_loss.item(), 
                            gene_corr_loss.item(), 
                            real_cls_loss.item(), 100 * real_cls_acc.item(), gen_cls_loss.item(), 100 * gen_cls_acc.item(),
                            cell_corr, cell_pval,
                            gene_corr,  gene_pval
                        )
                    )
                
            if opt.tensorboard:
                tensorboard_writer.add_scalar("validation_loss/disc_loss", disc_loss.item(), epoch)
                tensorboard_writer.add_scalar("validation_loss/adv_loss", adv_loss.item(), epoch)
                tensorboard_writer.add_scalar("validation_loss/recon_loss", recon_loss.item(), epoch)
                if opt.model == 'vae': tensorboard_writer.add_scalar("validation_loss/kl_loss", kl_loss.item(), epoch)
                elif opt.model == 'vqvae' or opt.model == 'resvqvae': tensorboard_writer.add_scalar("validation_loss/vq_loss", vq_loss.item(), epoch)
                tensorboard_writer.add_scalar("validation_loss/cell_corr_loss", cell_corr_loss.item(), epoch)
                tensorboard_writer.add_scalar("validation_loss/gene_corr_loss", gene_corr_loss.item(), epoch)
                tensorboard_writer.add_scalar("validation_loss/real_cls_loss", real_cls_loss.item(), epoch)
                tensorboard_writer.add_scalar("validation_loss/gen_cls_loss", gen_cls_loss.item(), epoch)
                tensorboard_writer.add_scalar("validation_accuracy/disc_acc", 100 * disc_acc.item(), epoch)
                tensorboard_writer.add_scalar("validation_accuracy/real_cls_acc", 100 * real_cls_acc.item(), epoch)
                tensorboard_writer.add_scalar("validation_accuracy/gen_cls_acc", 100 * gen_cls_acc.item(), epoch)
                tensorboard_writer.add_scalar("validation_correlation/cell_corr", cell_corr, epoch)
                tensorboard_writer.add_scalar("validation_correlation/cell_pval", cell_pval, epoch)
                tensorboard_writer.add_scalar("validation_correlation/gene_corr", gene_corr, epoch)
                tensorboard_writer.add_scalar("validation_correlation/gene_pval", gene_pval, epoch)
                tensorboard_writer.flush()
         
            val_disc_loss += disc_loss.item()
            val_disc_acc += 100 * disc_acc.item()
            val_adv_loss += adv_loss.item()
            val_recon_loss += recon_loss.item()
            if opt.model == 'vae': val_kl_loss += kl_loss.item()
            elif opt.model == 'vqvae' or opt.model == 'resvqvae': val_vq_loss += vq_loss.item()
            val_cell_corr_loss += cell_corr_loss.item()
            val_gene_corr_loss += gene_corr_loss.item()
            val_real_cls_loss += real_cls_loss.item()
            val_gen_cls_loss += gen_cls_loss.item()
            val_real_cls_acc += 100 * gen_cls_acc.item()
            val_gen_cls_acc += 100 * gen_cls_acc.item()
            val_cell_corr += cell_corr
            val_cell_pval += cell_pval
            val_gene_corr += gene_corr
            val_gene_pval += gene_pval
            
            val_count += 1
            
        val_disc_loss /= val_count
        val_disc_acc /= val_count
        val_real_cls_loss /= val_count
        val_gen_cls_loss /= val_count
        val_real_cls_acc /= val_count
        val_gen_cls_acc /= val_count
        val_adv_loss /= val_count
        val_recon_loss /= val_count
        if opt.model == 'vae': val_kl_loss /= val_count
        elif opt.model == 'vqvae' or opt.model == 'resvqvae': val_vq_loss /= val_count
        val_cell_corr_loss /= val_count
        val_gene_corr_loss /= val_count
        val_cell_corr /= val_count
        val_cell_pval /= val_count
        val_gene_corr /= val_count
        val_gene_pval /= val_count
        
        if opt.checkpoint_pass_rate > 0.0:
            if val_gen_cls_acc >= last_gen_cls_acc*opt.checkpoint_pass:
                last_gen_cls_acc = val_gen_cls_acc
        
                torch.save({
                    'generator_model_state_dict': generator.state_dict(),
                    'generator_optimizer_state_dict': optimizer_G.state_dict(),
                    'discriminator_model_state_dict': discriminator.state_dict(),
                    'discriminator_optimizer_state_dict': optimizer_D.state_dict(),
                    'parameters': param_json_data
                    }, os.path.join(opt.output_folder, "checkpoint.pt"))
            else:
        
                checkpoint = torch.load(os.path.join(opt.output_folder, "checkpoint.pt"))
                generator.load_state_dict(checkpoint['generator_model_state_dict'])
                optimizer_G.load_state_dict(checkpoint['generator_optimizer_state_dict'])
                discriminator.load_state_dict(checkpoint['discriminator_model_state_dict'])
                optimizer_D.load_state_dict(checkpoint['discriminator_optimizer_state_dict'])
                param_json_data = checkpoint['parameters']
                        
        if opt.test_interval != 0 and (epoch+1) % opt.test_interval == 0:
            validationH5adFilename = ''.join([
                opt.model_prefix+"validation",
                "-{}".format(epoch+1),
                "-{:.3f}".format(val_disc_loss),
                "-{:.3f}".format(val_disc_acc),
                "-{:.3f}".format(val_adv_loss),
                "-{:.3f}".format(val_recon_loss),
                "-{:.3f}".format(val_cell_corr_loss),
                "-{:.3f}".format(val_gene_corr_loss),
                "-{:.3f}".format(val_real_cls_loss),
                "-{:.3f}".format(val_gen_cls_loss),
                "-{:.3f}".format(val_real_cls_acc),
                "-{:.3f}".format(val_gen_cls_acc),
                "-{:.3f}".format(val_cell_corr),
                "-{:.3f}".format(val_cell_pval),
                "-{:.3f}".format(val_gene_corr),
                "-{:.3f}".format(val_gene_pval),
                ".h5ad"])
            
            generator.eval()
            discriminator.eval()
            # classifier.eval()        
                    
            expr_df = pd.DataFrame(columns=['Object ID']+param_json_data['gene_id'])
            cate_df = pd.DataFrame(columns=['Object ID', 'subtype'])
                    
            for i, (imgs, uuid, trns, sbtps) in enumerate(validationDataLoader):
                batch_size = imgs.shape[0]
        
                # Configure input
                real_imgs = Variable(imgs.type(FloatTensor))
        
                # Generate a batch of images
                gen_trns = generator(real_imgs)
        
                _, _, gen_cls = discriminator(gen_trns)
        
                # Calculate discriminator accuracy
                gen_pred = np.argmax(gen_cls.data.cpu().numpy(), axis=1)
                gen_trns =  np.log1p(gen_trns.data.cpu().numpy() * trns_count_per_cell)
                # gen_trns =  trns_log1p_minmaxscale * gen_trns.data.cpu().numpy()
                
                
                for j in range(batch_size):
                    expr_row = {'Object ID': uuid[j]}
                    expr_row.update({g:gen_trns[j][k] for k, (g) in enumerate(param_json_data['gene_id'])})
                    expr_df.loc[len(expr_df)] = expr_row
                    
                    cate_row = {'Object ID': uuid[j], 'subtype': gen_pred[j]}
                    cate_df.loc[len(cate_df)] = cate_row
                    
            expr_df.set_index('Object ID', inplace=True)        
            result_adata = ad.AnnData(csr_matrix(expr_df.values), dtype=np.float32)
            result_adata.obs_names = expr_df.index
            result_adata.var_names = expr_df.columns
            result_adata.obs.index = result_adata.obs.index.astype(str)
            result_adata.var_names_make_unique()
            
            cate_df.set_index('Object ID', inplace=True)   
            cate_df['subtype'] = cate_df['subtype'].astype('category')
            result_adata.obs = pd.concat([result_adata.obs, cate_df], axis=1)
            
            result_adata.write_h5ad(os.path.join(opt.output_folder, validationH5adFilename), compression='gzip')
            
           
        if opt.saving_interval != 0 and (epoch+1) % opt.saving_interval == 0:
            modelFilename = ''.join([
                opt.model_prefix+"model",
                "-{}".format(epoch+1),
                "-{:.3f}".format(val_disc_loss),
                "-{:.3f}".format(val_disc_acc),
                "-{:.3f}".format(val_adv_loss),
                "-{:.3f}".format(val_recon_loss),
                "-{:.3f}".format(val_cell_corr_loss),
                "-{:.3f}".format(val_gene_corr_loss),
                "-{:.3f}".format(val_real_cls_loss),
                "-{:.3f}".format(val_gen_cls_loss),
                "-{:.3f}".format(val_real_cls_acc),
                "-{:.3f}".format(val_gen_cls_acc),
                "-{:.3f}".format(val_cell_corr),
                "-{:.3f}".format(val_cell_pval),
                "-{:.3f}".format(val_gene_corr),
                "-{:.3f}".format(val_gene_pval),
                ".pt"])
            
            torch.save({'generator_model_state_dict': generator.state_dict(),
                        'generator_optimizer_state_dict': optimizer_G.state_dict(),
                        'discriminator_model_state_dict': discriminator.state_dict(),
                        'discriminator_optimizer_state_dict': optimizer_D.state_dict(),
                        'parameters': param_json_data
                        }, os.path.join(opt.output_folder, modelFilename))
               
    if opt.tensorboard:
        tensorboard_writer.close()
        
    torch.save({
        'generator_model_state_dict': generator.state_dict(),
        'generator_optimizer_state_dict': optimizer_G.state_dict(),
        'discriminator_model_state_dict': discriminator.state_dict(),
        'discriminator_optimizer_state_dict': optimizer_D.state_dict(),
        'parameters': param_json_data
        }, os.path.join(opt.output_folder, opt.model_prefix+'model-final.pt'))
    
    if opt.generate_report:
        with open(os.path.join(opt.output_folder, "description.txt"), 'w') as f:
            f.write("parameters: {}\n".format(vars(opt)))
            f.write("gene_id: {}\n".format(trns_data.columns.tolist()))
            # f.write("trns_count_per_cell: {}\n".format(trns_count_per_cell))
            f.write("gene_num: {}\n".format(gene_num))
            f.write("subtype_num: {}\n".format(subtype_num))
            f.write("image_std: {}\n".format(image_std.tolist()))
            f.write("image_mean: {}\n".format(image_mean.tolist()))
            f.write("val_disc_acc: {}\n".format(val_disc_acc))
            f.write("val_real_cls_acc: {}\n".format(val_real_cls_acc))
            f.write("val_gen_cls_acc: {}\n".format(val_gen_cls_acc))
            f.write("val_cell_corr: {}\n".format(val_cell_corr))
            f.write("val_cell_pval: {}\n".format(val_cell_pval))
            f.write("val_gene_corr: {}\n".format(val_gene_corr))
            f.write("val_gene_pval: {}\n".format(val_gene_pval))
        
    if opt.final_test:
        # Create image data according to training, validation and test uuids.
        total_image_data = image_data.reindex(total_uuid_list)
        
        # Create transcript data according to training, validation and test uuids.
        total_trns_data = trns_data.reindex(total_uuid_list).to_numpy()
        
        # Create sbtps data according to training, validation and test uuids.
        total_subtype_data = sbtps_data.reindex(total_uuid_list).astype(np.int32).to_numpy()
        
        # Create datasets
        total_transformed_dataset = SingleCellImageDataset(total_image_data, total_trns_data, total_subtype_data, image_fileext=existing_image_fileext, image_size=opt.image_size, image_mean=image_mean, image_std=image_std)
     
        # Create dataloaders
        total_data_loader = DataLoader(total_transformed_dataset, opt.batch_size, shuffle=True, num_workers=4, pin_memory=True)

        # total_csv_filename = opt.model_prefix+'validation-final.csv'
        
        generator.eval()
        discriminator.eval()
        # classifier.eval()        
                
        expr_df = pd.DataFrame(columns=['Object ID']+param_json_data['gene_id'])
        cate_df = pd.DataFrame(columns=['Object ID', 'subtype'])
                
        for i, (imgs, uuid, trns, sbtps) in enumerate(tqdm(total_data_loader)):
            batch_size = imgs.shape[0]
    
            # Configure input
            real_imgs = Variable(imgs.type(FloatTensor))
    
            # Generate a batch of images
            gen_trns = generator(real_imgs)
    
            _, _, gen_cls = discriminator(gen_trns)
    
            # Calculate discriminator accuracy
            gen_pred = np.argmax(gen_cls.data.cpu().numpy(), axis=1)
            gen_trns =  np.log1p(gen_trns.data.cpu().numpy() * trns_count_per_cell)
            # gen_trns =  trns_log1p_minmaxscale * gen_trns.data.cpu().numpy()
            
            
            for j in range(batch_size):
                expr_row = {'Object ID': uuid[j]}
                expr_row.update({g:gen_trns[j][k] for k, (g) in enumerate(param_json_data['gene_id'])})
                expr_df.loc[len(expr_df)] = expr_row
                
                cate_row = {'Object ID': uuid[j], 'subtype': gen_pred[j]}
                cate_df.loc[len(cate_df)] = cate_row
                
        expr_df.set_index('Object ID', inplace=True)        
        result_adata = ad.AnnData(csr_matrix(expr_df.values), dtype=np.float32)
        result_adata.obs_names = expr_df.index
        result_adata.var_names = expr_df.columns
        result_adata.obs.index = result_adata.obs.index.astype(str)
        result_adata.var_names_make_unique()
        
        cate_df.set_index('Object ID', inplace=True)   
        cate_df['subtype'] = cate_df['subtype'].astype('category')
        result_adata.obs = pd.concat([result_adata.obs, cate_df], axis=1)
        
        result_adata.write_h5ad(os.path.join(opt.output_folder, opt.model_prefix+'validation-final.h5ad'), compression='gzip')
        
        
