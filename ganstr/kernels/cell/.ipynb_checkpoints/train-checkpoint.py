import glob
import pandas as pd
import os
from PIL import Image
import argparse
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
from utils import SingleCellImageDataset, weights_init_normal
from tqdm import tqdm
from torchviz import make_dot
import warnings
from tensorboard import program
import webbrowser
    
def train(opt):
    # Tensorboard
    if opt.tensorboard:
        tb = program.TensorBoard()
        tb.configure(argv=[None, '--logdir', os.path.join(opt.output_folder, "runs"), '--port', opt.tensorboard_port])
        url = tb.launch()
        print(f"Tensorboard listening on {url}")
        webbrowser.open(url)
    
        tensorboardWriter = SummaryWriter(os.path.join(opt.output_folder, "runs"))
    
    # GPU environment by CUDA
    cuda = True if torch.cuda.is_available() else False

    # Loss functions
    adversarial_loss = torch.nn.BCELoss()
    auxiliary_loss = torch.nn.CrossEntropyLoss()
    reconstruction_loss = torch.nn.L1Loss() if opt.l1_loss else torch.nn.MSELoss()
    cell_correlation_loss = PearsonCorrLoss(dim=1)
    gene_correlation_loss = PearsonCorrLoss(dim=0)
    classification_loss = torch.nn.CrossEntropyLoss()
    
    # Prepare transcript data
    trnsData = pd.read_csv(opt.transcript_file)
    trnsData.dropna(axis=1, how='all', inplace=True) # Remove columns of all NaN
    trnsData.dropna(inplace=True) # Drop rows containing NaN
    trnsData.set_index("Object ID", inplace=True)
    
    sbtpsData = trnsData[["subtypes"]].copy()
    trnsData.drop(["subtypes"], axis=1,inplace=True)
    geneNum = len(trnsData.columns)
    subtypeNum = len(sbtpsData['subtypes'].unique())
    assert np.expm1(trnsData.sample(1000)).sum(axis=1).std() < 1e-5, "transcript data is not normalized"
    trnsCountPerCell = np.expm1(trnsData.sample(1000)).sum(axis=1).mean()
    
    # Prepare image data
    imageFilePathList = []
    imageFileUuidList = []
    existingImageFileExt = None
    imageFileNameList = glob.glob(os.path.join(opt.image_folder, '*','*'))
    
    # Normalization 
    sampledImageFileNameList = sample(imageFileNameList,100)
    sampledImageArrayList = []
    
    for sifn in sampledImageFileNameList:
        imgBuf = Image.open(sifn)
        imgAry = np.array(imgBuf).astype(np.float32)
        
        sampledImageArrayList.append(imgAry.reshape((1, *imgAry.shape)))
    
    sampledImageArrayListConcat = np.concatenate(sampledImageArrayList)
    imageMean = sampledImageArrayListConcat.mean(axis=(0,1,2))
    imageStd = (sampledImageArrayListConcat-imageMean).std(axis=(0,1,2))
    
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
    totalUuidList = [value for value in imageData.index if value in trnsData.index if value in sbtpsData.index]
    
    # Split all data into training, validation and test uuids.
    trainingUuidList = totalUuidList[:int(len(totalUuidList)*0.9)]
    validationUuidList = totalUuidList[int(len(totalUuidList)*0.9):]
    
    # Create image data according to training, validation and test uuids.
    trainingImageData = imageData.reindex(trainingUuidList)
    validationImageData = imageData.reindex(validationUuidList)
    
    # Create transcript data according to training, validation and test uuids.
    trainingTrnsData = trnsData.reindex(trainingUuidList)
    validationTrnsData = trnsData.reindex(validationUuidList)
    
    # Create sbtps data according to training, validation and test uuids.
    trainingSubtypeData = sbtpsData.reindex(trainingUuidList)
    validationSubtypeData = sbtpsData.reindex(validationUuidList)
    
    # Create datasets
    trainingTransformedDataset = SingleCellImageDataset(trainingImageData, trainingTrnsData, trainingSubtypeData, imageFileExt=existingImageFileExt, imageSize=opt.image_size, imageMean=imageMean, imageStd=imageStd, trnsCountPerCell=trnsCountPerCell)
    validationTransformedDataset = SingleCellImageDataset(validationImageData, validationTrnsData, validationSubtypeData, imageFileExt=existingImageFileExt, imageSize=opt.image_size, imageMean=imageMean, imageStd=imageStd, trnsCountPerCell=trnsCountPerCell)
    
    # Create dataloaders
    trainingDataLoader = DataLoader(trainingTransformedDataset, opt.batch_size, shuffle=True, num_workers=4, pin_memory=True)
    validationDataLoader = DataLoader(validationTransformedDataset, opt.batch_size, shuffle=True, num_workers=4, pin_memory=True)
      
    # Initialize generator and discriminator      
    if opt.model == 'ae':
        generator = ae.Generator(geneNum=geneNum, latentDim=opt.latent_dim)
        discriminator = ae.Discriminator(geneNum=geneNum, subtypeNum=subtypeNum)
        classifier = ae.Classifier(geneNum=geneNum, subtypeNum=subtypeNum)
    elif opt.model == 'vae':
        generator = vae.Generator(geneNum=geneNum, latentDim=opt.latent_dim)
        discriminator = vae.Discriminator(geneNum=geneNum, subtypeNum=subtypeNum)
        classifier = vae.Classifier(geneNum=geneNum, subtypeNum=subtypeNum)
    elif opt.model == 'vqvae':
        generator = vqvae.Generator(geneNum=geneNum, latentDim=opt.latent_dim)
        discriminator = vqvae.Discriminator(geneNum=geneNum, subtypeNum=subtypeNum)
        classifier = vqvae.Classifier(geneNum=geneNum, subtypeNum=subtypeNum)
    elif opt.model == 'resvqvae':
        generator = resvqvae.Generator(geneNum=geneNum, latentDim=opt.latent_dim)
        discriminator = resvqvae.Discriminator(geneNum=geneNum, subtypeNum=subtypeNum)
        classifier = resvqvae.Classifier(geneNum=geneNum, subtypeNum=subtypeNum)
    else:
        raise Exception("The chosen model is not supported")
            
    if cuda:
        generator.cuda()
        discriminator.cuda()
        classifier.cuda()
        adversarial_loss.cuda()
        auxiliary_loss.cuda()
        reconstruction_loss.cuda()
        cell_correlation_loss.cuda()
        gene_correlation_loss.cuda()
        classification_loss.cuda()
    
    # Initialize weights
    generator.apply(weights_init_normal)
    discriminator.apply(weights_init_normal)
    classifier.apply(weights_init_normal)
    
    # Optimizers
    optimizer_G = torch.optim.Adam(generator.parameters(), lr=opt.learning_rate, betas=(opt.beta_1, opt.beta_2))
    optimizer_D = torch.optim.Adam(discriminator.parameters(), lr=opt.learning_rate, betas=(opt.beta_1, opt.beta_2))
    optimizer_C = torch.optim.Adam(classifier.parameters(), lr=opt.learning_rate, betas=(opt.beta_1, opt.beta_2))
    
    # Tensor types
    FloatTensor = torch.cuda.FloatTensor if cuda else torch.FloatTensor
    LongTensor = torch.cuda.LongTensor if cuda else torch.LongTensor 
    
    if opt.generate_graphs:
        imgs, trns, sbtps, uuid = next(iter(trainingDataLoader))
        real_imgs = Variable(imgs.type(FloatTensor))
        gen_trns = generator(real_imgs)
        validity, _ = discriminator(gen_trns)
        label = classifier(gen_trns)
        
        make_dot(gen_trns, params=dict(list(generator.named_parameters()))).render(filename=os.path.join(opt.output_folder, opt.model_prefix+"generator"), format="png")
        make_dot(validity, params=dict(list(discriminator.named_parameters()))).render(os.path.join(opt.output_folder, opt.model_prefix+"discriminator"), format="png")
        make_dot(label, params=dict(list(classifier.named_parameters()))).render(os.path.join(opt.output_folder, opt.model_prefix+"classifier"), format="png")
        
    if opt.tensorboard:
        class Wrapper(torch.nn.Module):
            def __init__(self, generator, discriminator, classifier):
                super(Wrapper, self).__init__()
                self.generator = generator
                self.discriminator = discriminator
                self.classifier = classifier
    
            def forward(self, imgs):
                real_imgs = Variable(imgs.type(FloatTensor))
                gen_trns = self.generator(real_imgs)
                validity, aux = self.discriminator(gen_trns)
                label = self.classifier(gen_trns)
            
                return validity, aux, label
        
        wrapper = Wrapper(generator, discriminator, classifier)
        imgs, _, _, _ = next(iter(trainingDataLoader))
        real_imgs = Variable(imgs.type(FloatTensor))
        tensorboardWriter.add_graph(wrapper, real_imgs)
               
               
    paramJSONData = {
                "parameters": vars(opt),
                # "generatorModelFile": generatorModelFilename,
                # "discriminatorModelFile": discriminatorModelFilename,
                # "modelFile": modelFilename,
                "geneIDs": trnsData.columns.tolist(),
                "trnsCountPerCell": trnsCountPerCell,
                "geneNum": geneNum,
                "subtypeNum": subtypeNum,
                "imageStd": imageStd.tolist(),
                "imageMean": imageMean.tolist(),
                }
             
    # ----------
    #  Training
    # ----------
    
    last_cls_acc = 0
    
    optimizationRange = range(opt.n_epochs) if opt.detailed_progress else tqdm(range(opt.n_epochs), total=opt.n_epochs)
    for epoch in optimizationRange:
        # -----------------
        # Training Loop
        # -----------------
        generator.train()
        discriminator.train()
        classifier.train()
    
        for i, (imgs, uuid, trns, sbtps) in enumerate(trainingDataLoader):
    
            batch_size = imgs.shape[0]
    
            # Adversarial ground truths
            valid = Variable(FloatTensor(batch_size, 1).fill_(1.0), requires_grad=False)
            fake = Variable(FloatTensor(batch_size, 1).fill_(0.0), requires_grad=False)
            fake_aux_gt = Variable(LongTensor(batch_size).fill_(subtypeNum), requires_grad=False)
    
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
            validity, _ = discriminator(gen_trns)
            
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
            real_pred, real_aux = discriminator(real_trns)
            d_real_loss = (adversarial_loss(real_pred, valid) + auxiliary_loss(real_aux, real_sbtps)) / 2
    
            # Loss for fake images
            fake_pred, fake_aux = discriminator(gen_trns.detach())
            d_fake_loss = (adversarial_loss(fake_pred, fake) + auxiliary_loss(fake_aux, fake_aux_gt)) / 2
    
            # Total discriminator loss
            disc_loss = (d_real_loss + d_fake_loss) / 2
            disc_loss_weighted = opt.discrimination_weight*disc_loss
    
            disc_loss_weighted.backward()
            optimizer_D.step()
    
            # Calculate discriminator accuracy
            pred = np.concatenate([real_aux.data.cpu().numpy(), fake_aux.data.cpu().numpy()], axis=0)
            gt = np.concatenate([real_sbtps.data.cpu().numpy(), fake_aux_gt.data.cpu().numpy()], axis=0)
            disc_acc = np.mean(np.argmax(pred, axis=1) == gt)
    
            # ---------------------
            #  Train Classifier
            # ---------------------
            
            optimizer_C.zero_grad()
    
            # Loss for predicted trns
            gen_trns = generator(real_imgs)
            gen_labels = classifier(gen_trns)
            cls_loss = classification_loss(gen_labels, real_sbtps)
    
            # Total classification loss
            cls_loss_weighted = opt.classification_weight*cls_loss
    
            cls_loss_weighted.backward()
            optimizer_C.step()
    
            # Calculate discriminator accuracy
            cls_acc = np.mean(np.argmax(gen_labels.data.cpu().numpy(), axis=1) == real_sbtps.data.cpu().numpy())
            
            # ---------------------
            #  Training Finished
            # ---------------------
        
            with warnings.catch_warnings():
                warnings.simplefilter("ignore")
                # Calculate Spearman's correlation over cells
                cellSpmList = [spearmanr(gen_trns.detach().cpu().numpy()[i], trns.detach().cpu().numpy()[i]) for i in range(batch_size)]
                cell_corr, cell_pval = np.asarray(cellSpmList).mean(axis=0)
                
                geneSpmList = [spearmanr(gen_trns.detach().cpu().numpy()[:, i], trns.detach().cpu().numpy()[:, i]) for i in range(geneNum)]
                geneSpmAry = np.asarray(geneSpmList)
                
                geneSpmNanIdx = np.argwhere(np.isnan(geneSpmAry).any(axis=1)).reshape(-1).tolist()
                geneSpmAry = np.delete(geneSpmAry, geneSpmNanIdx, 0)
                
                gene_corr, gene_pval = geneSpmAry.mean(axis=0)
                
            if opt.detailed_progress:
                print(
                    "Training "+
                    "[Epoch {:}/{:}] "+
                    "[Batch {}/{}] "+
                    "[D loss: {:.3f}, acc: {:.2f}%] "+
                    "[A loss: {:.3f}] "+
                    "[R loss: {:.3f}] "+
                    "[C loss: {:.3f}/{:.3f}] "+
                    "[L loss: {:.3f}, acc: {:.2f}%] "+
                    "[Cell corr: {:.3f}/pval: {:.3f}]"+
                    "[Gene corr: {:.3f}/pval: {:.3f}]"
                    .format(epoch, 
                            opt.n_epochs, i, len(trainingDataLoader), 
                            disc_loss.item(), 100 * disc_acc.item(), 
                            adv_loss.item(),
                            recon_loss.item(), 
                            cell_corr_loss.item(), 
                            gene_corr_loss.item(), 
                            cls_loss.item(), 100 * cls_acc.item(), 
                            cell_corr, 
                            cell_pval,
                            gene_corr, 
                            gene_pval)
                    )
            
            if opt.tensorboard:
                tensorboardWriter.add_scalar("training_loss/disc_loss", disc_loss.item(), epoch)
                tensorboardWriter.add_scalar("training_loss/adv_loss", adv_loss.item(), epoch)
                tensorboardWriter.add_scalar("training_loss/recon_loss", recon_loss.item(), epoch)
                if opt.model == 'vae': tensorboardWriter.add_scalar("training_loss/kl_loss", kl_loss.item(), epoch)
                elif opt.model == 'vqvae' or opt.model == 'resvqvae': tensorboardWriter.add_scalar("training_loss/vq_loss", vq_loss.item(), epoch)
                tensorboardWriter.add_scalar("training_loss/cell_corr_loss", cell_corr_loss.item(), epoch)
                tensorboardWriter.add_scalar("training_loss/gene_corr_loss", gene_corr_loss.item(), epoch)
                tensorboardWriter.add_scalar("training_loss/cls_loss", cls_loss.item(), epoch)
                tensorboardWriter.add_scalar("training_accuracy/disc_acc", 100 * disc_acc.item(), epoch)
                tensorboardWriter.add_scalar("training_accuracy/cls_acc", 100 * cls_acc.item(), epoch)
                tensorboardWriter.add_scalar("training_correlation/cell_corr", cell_corr, epoch)
                tensorboardWriter.add_scalar("training_correlation/cell_pval", cell_pval, epoch)
                tensorboardWriter.add_scalar("training_correlation/gene_corr", gene_corr, epoch)
                tensorboardWriter.add_scalar("training_correlation/gene_pval", gene_pval, epoch)
                
                for m in [generator, discriminator, classifier]:
                    for tag, value in m.named_parameters():
                        if value is not None:
                            tensorboardWriter.add_histogram("training/values/" + tag, value.cpu(), epoch)
                            
                        if value.grad is not None:
                            tensorboardWriter.add_histogram("training/grads/" + tag, value.grad.cpu(), epoch)
                    
                tensorboardWriter.flush()
    
        # -----------------
        # Validation Loop
        # -----------------
        
        generator.eval()
        discriminator.eval()
        classifier.eval()
        
        val_disc_loss = 0
        val_disc_acc = 0
        val_adv_loss = 0
        val_recon_loss = 0
        if opt.model == 'vae': val_kl_loss = 0
        elif opt.model == 'vqvae' or opt.model == 'resvqvae': val_vq_loss = 0
        val_cell_corr_loss = 0
        val_gene_corr_loss = 0
        val_cls_loss = 0
        val_cls_acc = 0
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
            fake_aux_gt = Variable(LongTensor(batch_size).fill_(subtypeNum), requires_grad=False)
    
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
            validity, _ = discriminator(gen_trns)
            
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
            real_pred, real_aux = discriminator(real_trns)
            d_real_loss = (adversarial_loss(real_pred, valid) + auxiliary_loss(real_aux, real_sbtps)) / 2
    
            # Loss for fake images
            fake_pred, fake_aux = discriminator(gen_trns.detach())
            d_fake_loss = (adversarial_loss(fake_pred, fake) + auxiliary_loss(fake_aux, fake_aux_gt)) / 2
    
            # Total discriminator loss
            disc_loss = (d_real_loss + d_fake_loss) / 2
    
            # Calculate discriminator accuracy
            pred = np.concatenate([real_aux.data.cpu().numpy(), fake_aux.data.cpu().numpy()], axis=0)
            gt = np.concatenate([real_sbtps.data.cpu().numpy(), fake_aux_gt.data.cpu().numpy()], axis=0)
            disc_acc = np.mean(np.argmax(pred, axis=1) == gt)
    
            # -------------------------
            #  Validation Classifier
            # -------------------------
            
            # Loss for predicted trns
            gen_labels = classifier(gen_trns)
            cls_loss = classification_loss(gen_labels, real_sbtps)
    
            # Calculate discriminator accuracy
            cls_acc = np.mean(np.argmax(gen_labels.data.cpu().numpy(), axis=1) == real_sbtps.data.cpu().numpy())
            
            # -------------------------
            #  Training Finished
            # -------------------------
                    
            with warnings.catch_warnings():
                warnings.simplefilter("ignore")
                # Calculate Spearman's correlation over cells
                cellSpmList = [spearmanr(gen_trns.detach().cpu().numpy()[i], trns.detach().cpu().numpy()[i]) for i in range(batch_size)]
                cell_corr, cell_pval = np.asarray(cellSpmList).mean(axis=0)
                
                geneSpmList = [spearmanr(gen_trns.detach().cpu().numpy()[:, i], trns.detach().cpu().numpy()[:, i]) for i in range(geneNum)]
                geneSpmAry = np.asarray(geneSpmList)
                
                geneSpmNanIdx = np.argwhere(np.isnan(geneSpmAry).any(axis=1)).reshape(-1).tolist()
                geneSpmAry = np.delete(geneSpmAry, geneSpmNanIdx, 0)
                
                gene_corr, gene_pval = geneSpmAry.mean(axis=0)
            
            if opt.detailed_progress:
                print(
                    "Training "+
                    "[Epoch {:}/{:}] "+
                    "[Batch {}/{}] "+
                    "[D loss: {:.3f}, acc: {:.2f}%] "+
                    "[A loss: {:.3f}] "+
                    "[R loss: {:.3f}] "+
                    "[C loss: {:.3f}/{:.3f}] "+
                    "[L loss: {:.3f}, acc: {:.2f}%] "+
                    "[Cell corr: {:.3f}/pval: {:.3f}]"+
                    "[Gene corr: {:.3f}/pval: {:.3f}]"
                    .format(epoch, 
                            opt.n_epochs, i, len(trainingDataLoader), 
                            disc_loss.item(), 100 * disc_acc.item(), 
                            adv_loss.item(),
                            recon_loss.item(), 
                            cell_corr_loss.item(), 
                            gene_corr_loss.item(), 
                            cls_loss.item(), 100 * cls_acc.item(), 
                            cell_corr, 
                            cell_pval,
                            gene_corr, 
                            gene_pval)
                    )
                
                
            if opt.tensorboard:
                tensorboardWriter.add_scalar("validation_loss/disc_loss", disc_loss.item(), epoch)
                tensorboardWriter.add_scalar("validation_loss/adv_loss", adv_loss.item(), epoch)
                tensorboardWriter.add_scalar("validation_loss/recon_loss", recon_loss.item(), epoch)
                if opt.model == 'vae': tensorboardWriter.add_scalar("validation_loss/kl_loss", kl_loss.item(), epoch)
                elif opt.model == 'vqvae' or opt.model == 'resvqvae': tensorboardWriter.add_scalar("validation_loss/kl_loss", vq_loss.item(), epoch)
                tensorboardWriter.add_scalar("validation_loss/cell_corr_loss", cell_corr_loss.item(), epoch)
                tensorboardWriter.add_scalar("validation_loss/gene_corr_loss", gene_corr_loss.item(), epoch)
                tensorboardWriter.add_scalar("validation_loss/cls_loss", cls_loss.item(), epoch)
                tensorboardWriter.add_scalar("validation_accuracy/disc_acc", 100 * disc_acc.item(), epoch)
                tensorboardWriter.add_scalar("validation_accuracy/cls_acc", 100 * cls_acc.item(), epoch)
                tensorboardWriter.add_scalar("validation_correlation/cell_corr", cell_corr, epoch)
                tensorboardWriter.add_scalar("validation_correlation/cell_pval", cell_pval, epoch)
                tensorboardWriter.add_scalar("validation_correlation/gene_corr", gene_corr, epoch)
                tensorboardWriter.add_scalar("validation_correlation/gene_pval", gene_pval, epoch)
                tensorboardWriter.flush()
         
            val_disc_loss += disc_loss.item()
            val_disc_acc += 100 * disc_acc.item()
            val_adv_loss += adv_loss.item()
            val_recon_loss += recon_loss.item()
            if opt.model == 'vae': val_kl_loss += kl_loss.item()
            elif opt.model == 'vqvae' or opt.model == 'resvqvae': val_vq_loss += vq_loss.item()
            val_cell_corr_loss += cell_corr_loss.item()
            val_gene_corr_loss += gene_corr_loss.item()
            val_cls_loss += cls_loss.item()
            val_cls_acc += 100 * cls_acc.item()
            val_cell_corr += cell_corr
            val_cell_pval += cell_pval
            val_gene_corr += gene_corr
            val_gene_pval += gene_pval
            
            val_count += 1
            
        val_disc_loss /= val_count
        val_disc_acc /= val_count
        val_cls_acc /= val_count
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
        
        # if opt.tensorboard:
        #     tensorboardWriter.add_scalar("validation_loss/disc_loss", val_disc_loss, epoch)
        #     tensorboardWriter.add_scalar("validation_loss/adv_loss", val_adv_loss, epoch)
        #     tensorboardWriter.add_scalar("validation_loss/recon_loss", val_recon_loss, epoch)
        #     if opt.model == 'vae': tensorboardWriter.add_scalar("validation_loss/kl_loss", val_kl_loss, epoch)
        #     elif opt.model == 'vqvae' or opt.model == 'resvqvae': tensorboardWriter.add_scalar("validation_loss/kl_loss", val_vq_loss, epoch)
        #     tensorboardWriter.add_scalar("validation_loss/corr_loss", val_corr_loss, epoch)
        #     tensorboardWriter.add_scalar("validation_loss/cls_loss", val_cls_loss, epoch)
        #     tensorboardWriter.add_scalar("validation_accuracy/disc_acc", val_disc_acc, epoch)
        #     tensorboardWriter.add_scalar("validation_accuracy/cls_acc", val_cls_acc, epoch)
        #     tensorboardWriter.add_scalar("validation_correlation/cell_corr", val_cell_corr, epoch)
        #     tensorboardWriter.add_scalar("validation_correlation/cell_pval", val_cell_pval, epoch)
        #     tensorboardWriter.add_scalar("validation_correlation/gene_corr", val_gene_corr, epoch)
        #     tensorboardWriter.add_scalar("validation_correlation/gene_pval", val_gene_pval, epoch)
        #     tensorboardWriter.flush()
    
        
        if opt.checkpoint_pass > 0.0:
            if val_cls_acc >= last_cls_acc*opt.checkpoint_pass:
                last_cls_acc = val_cls_acc
            
                torch.save({
                    'generator_model_state_dict': generator.state_dict(),
                    'generator_optimizer_state_dict': optimizer_G.state_dict(),
                    'discriminator_model_state_dict': discriminator.state_dict(),
                    'discriminator_optimizer_state_dict': optimizer_D.state_dict(),
                    'classifier_model_state_dict': classifier.state_dict(),
                    'classifier_optimizer_state_dict': optimizer_C.state_dict(),
                    'parameters': paramJSONData
                    }, os.path.join(opt.output_folder, "checkpoint.pt"))
            else:
            
                checkpoint = torch.load(os.path.join(opt.output_folder, "checkpoint.pt"))
                generator.load_state_dict(checkpoint['generator_model_state_dict'])
                optimizer_G.load_state_dict(checkpoint['generator_optimizer_state_dict'])
                discriminator.load_state_dict(checkpoint['discriminator_model_state_dict'])
                optimizer_D.load_state_dict(checkpoint['discriminator_optimizer_state_dict'])
                classifier.load_state_dict(checkpoint['classifier_model_state_dict'])
                optimizer_C.load_state_dict(checkpoint['classifier_optimizer_state_dict'])
                paramJSONData = checkpoint['parameters']
                        
        if opt.test_interval != 0 and (epoch+1) % opt.test_interval == 0:
            validationCsvFilename = ''.join([
                opt.model_prefix+"validation",
                str(epoch+1),
                "-{:.3f}".format(val_disc_loss),
                "-{:.3f}".format(val_disc_acc),
                "-{:.3f}".format(val_adv_loss),
                "-{:.3f}".format(val_recon_loss),
                "-{:.3f}".format(val_cell_corr_loss),
                "-{:.3f}".format(val_gene_corr_loss),
                "-{:.3f}".format(val_cls_loss),
                "-{:.3f}".format(val_cls_acc),
                "-{:.3f}".format(val_cell_corr),
                "-{:.3f}".format(val_cell_pval),
                "-{:.3f}".format(val_gene_corr),
                "-{:.3f}".format(val_gene_pval),
                ".csv"])
            
            with open(os.path.join(opt.output_folder, opt.model_prefix+validationCsvFilename), "w", newline='') as f:
                csvWriter = csv.writer(f)
                csvWriter.writerow(['Object ID','subtype']+trnsData.columns.tolist())
                
                for i, (imgs, uuid, trns, sbtps) in enumerate(validationDataLoader):
                    batch_size = imgs.shape[0]
                    
                    # Configure input
                    real_imgs = Variable(imgs.type(FloatTensor))
                    
                    # Generate a batch of images
                    gen_trns = generator(real_imgs)
                    
                    # Loss for fake images
                    _, fake_aux = discriminator(gen_trns)
            
                    # Calculate discriminator accuracy
                    prob = fake_aux.data.cpu().numpy()
                    pred = np.argmax(prob, axis=1)
            
                    gen_trns *= trnsCountPerCell
                
                    for j in range(batch_size):
                        row = [uuid[j], pred[j].astype(np.int32)]+gen_trns.data.cpu().numpy()[j].tolist()
                        csvWriter.writerow(row)
         
        if (epoch == opt.n_epochs-1) or (opt.saving_interval != 0 and (epoch+1) % opt.saving_interval == 0):
            # generatorModelFilename = '-'.join([
            #     opt.model_prefix+"generator_model",
            #     str(epoch+1),
            #     "{:.3f}".format(val_disc_loss),
            #     "{:.3f}".format(val_disc_acc),
            #     "{:.3f}".format(val_adv_loss),
            #     "{:.3f}".format(val_recon_loss),
            #     "{:.3f}".format(val_corr_loss),
            #     "{:.3f}".format(val_cls_loss),
            #     "{:.3f}".format(val_cls_acc),
            #     "{:.3f}".format(val_cell_corr),
            #     "{:.3f}".format(val_cell_pval),
            #     "{:.3f}".format(val_gene_corr),
            #     "{:.3f}".format(val_gene_pval),
            #     ".pt"])
            #
            # discriminatorModelFilename = '-'.join([
            #     opt.model_prefix+"discriminator_model",
            #     str(epoch+1),
            #     "{:.3f}".format(val_disc_loss),
            #     "{:.3f}".format(val_disc_acc),
            #     "{:.3f}".format(val_adv_loss),
            #     "{:.3f}".format(val_recon_loss),
            #     "{:.3f}".format(val_corr_loss),
            #     "{:.3f}".format(val_cls_loss),
            #     "{:.3f}".format(val_cls_acc),
            #     "{:.3f}".format(val_cell_corr),
            #     "{:.3f}".format(val_cell_pval),
            #     "{:.3f}".format(val_gene_corr),
            #     "{:.3f}".format(val_gene_pval),
            #     ".pt"])
            #
            # classifierModelFilename = '-'.join([
            #     opt.model_prefix+"classifier_model",
            #     str(epoch+1),
            #     "{:.3f}".format(val_disc_loss),
            #     "{:.3f}".format(val_disc_acc),
            #     "{:.3f}".format(val_adv_loss),
            #     "{:.3f}".format(val_recon_loss),
            #     "{:.3f}".format(val_corr_loss),
            #     "{:.3f}".format(val_cls_loss),
            #     "{:.3f}".format(val_cls_acc),
            #     "{:.3f}".format(val_cell_corr),
            #     "{:.3f}".format(val_cell_pval),
            #     "{:.3f}".format(val_gene_corr),
            #     "{:.3f}".format(val_gene_pval),
            #     ".pt"])
            #
            # torch.save(generator, os.path.join(opt.output_folder, generatorModelFilename))
            # torch.save(discriminator, os.path.join(opt.output_folder, discriminatorModelFilename))
            # torch.save(classifier, os.path.join(opt.output_folder, classifierModelFilename))
            
            modelFilename = ''.join([
                opt.model_prefix+"model",
                str(epoch+1),
                "-{:.3f}".format(val_disc_loss),
                "-{:.3f}".format(val_disc_acc),
                "-{:.3f}".format(val_adv_loss),
                "-{:.3f}".format(val_recon_loss),
                "-{:.3f}".format(val_cell_corr_loss),
                "-{:.3f}".format(val_gene_corr_loss),
                "-{:.3f}".format(val_cls_loss),
                "-{:.3f}".format(val_cls_acc),
                "-{:.3f}".format(val_cell_corr),
                "-{:.3f}".format(val_cell_pval),
                "-{:.3f}".format(val_gene_corr),
                "-{:.3f}".format(val_gene_pval),
                ".pt"])
            
            # paramJSONData = {
            #     "parameters": vars(opt),
            #     # "generatorModelFile": generatorModelFilename,
            #     # "discriminatorModelFile": discriminatorModelFilename,
            #     # "modelFile": modelFilename,
            #     "geneIDs": trnsData.columns.tolist(),
            #     "trnsCountPerCell": trnsCountPerCell,
            #     "geneNum": geneNum,
            #     "subtypeNum": subtypeNum,
            #     "imageStd": imageStd.tolist(),
            #     "imageMean": imageMean.tolist(),
            #     }
            
            torch.save({
                    'generator_model_state_dict': generator.state_dict(),
                    'generator_optimizer_state_dict': optimizer_G.state_dict(),
                    'discriminator_model_state_dict': discriminator.state_dict(),
                    'discriminator_optimizer_state_dict': optimizer_D.state_dict(),
                    'classifier_model_state_dict': classifier.state_dict(),
                    'classifier_optimizer_state_dict': optimizer_C.state_dict(),
                    'parameters': paramJSONData
                    }, os.path.join(opt.output_folder, modelFilename))
            
    
            # # Save JSON Here
            # paramJSONData = {
            #     "parameters": vars(opt),
            #     # "generatorModelFile": generatorModelFilename,
            #     # "discriminatorModelFile": discriminatorModelFilename,
            #     "modelFile": modelFilename,
            #     "geneIDs": trnsData.columns.tolist(),
            #     "trnsCountPerCell": trnsCountPerCell,
            #     "geneNum": geneNum,
            #     "subtypeNum": subtypeNum,
            #     "imageStd": imageStd.tolist(),
            #     "imageMean": imageMean.tolist(),
            #     }
            #
            # with open(os.path.join(opt.output_folder, opt.model_prefix+"parameters.json"), "w") as f:
            #     json.dump(paramJSONData, f, ensure_ascii=False, indent=4)         
               
    if opt.tensorboard:
        tensorboardWriter.close()
        
    torch.save({
        'generator_model_state_dict': generator.state_dict(),
        'generator_optimizer_state_dict': optimizer_G.state_dict(),
        'discriminator_model_state_dict': discriminator.state_dict(),
        'discriminator_optimizer_state_dict': optimizer_D.state_dict(),
        'classifier_model_state_dict': classifier.state_dict(),
        'classifier_optimizer_state_dict': optimizer_C.state_dict(),
        'parameters': paramJSONData
        }, os.path.join(opt.output_folder, "model-final.pt"))
    
    if opt.final_test:
        print("Final total test")
        # Create image data according to training, validation and test uuids.
        totalImageData = imageData.reindex(totalUuidList)
        
        # Create transcript data according to training, validation and test uuids.
        totalTrnsData = trnsData.reindex(totalUuidList)
        
        # Create sbtps data according to training, validation and test uuids.
        totalSubtypeData = sbtpsData.reindex(totalUuidList)
        
        # Create datasets
        totalTransformedDataset = SingleCellImageDataset(totalImageData, totalTrnsData, totalSubtypeData, imageFileExt=existingImageFileExt, imageSize=opt.image_size, imageMean=imageMean, imageStd=imageStd, trnsCountPerCell=trnsCountPerCell)
        
        # Create dataloaders
        totalDataLoader = DataLoader(totalTransformedDataset, opt.batch_size, shuffle=True, num_workers=4, pin_memory=True)
         
    
        totalCsvFilename = opt.model_prefix+'validation-final.csv'
        
        with open(os.path.join(opt.output_folder, opt.model_prefix+totalCsvFilename), "w", newline='') as f:
            csvWriter = csv.writer(f)
            csvWriter.writerow(['Object ID','subtype']+paramJSONData['geneIDs'])
            
            for i, (imgs, uuid, trns, sbtps) in enumerate(tqdm(totalDataLoader)):
                batch_size = imgs.shape[0]
                
                # Configure input
                real_imgs = Variable(imgs.type(FloatTensor))
                
                # Generate a batch of images
                gen_trns = generator(real_imgs)
                
                label = classifier(gen_trns)
                # Loss for fake images
                # _, fake_aux = discriminator(gen_trns)
        
                # Calculate discriminator accuracy
                # prob = fake_aux.data.cpu().numpy()
                pred = np.argmax(label.data.cpu().numpy(), axis=1)
        
                gen_trns *= trnsCountPerCell
            
                for j in range(batch_size):
                    row = [uuid[j], pred[j].astype(np.int32)]+gen_trns.data.cpu().numpy()[j].tolist()
                    csvWriter.writerow(row)
                    
    
    
    
    
    
