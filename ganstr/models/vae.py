import torch
import torch.nn as nn

cuda = True if torch.cuda.is_available() else False

FloatTensor = torch.cuda.FloatTensor if cuda else torch.FloatTensor
LongTensor = torch.cuda.LongTensor if cuda else torch.LongTensor 
EPSILON = torch.finfo(torch.float32).eps # or  torch.tensor(torch.finfo(torch.float32).eps)

class Generator(nn.Module):
    def __init__(self, geneNum, latentDim):
        super(Generator, self).__init__()
        
        self.convBlocks = nn.Sequential(
            nn.BatchNorm2d(3),
            # 48 x 48 x 3
            nn.Conv2d(3, 32, kernel_size = 3, padding = 1),
            nn.LeakyReLU(0.1, inplace=True), #nn.ReLU(inplace=True),
            # nn.Dropout2d(0.25),
            nn.BatchNorm2d(32),
            nn.MaxPool2d(2, 2),
            # 24 x 24 x 32
            nn.Conv2d(32 ,64, kernel_size = 3, stride = 1, padding = 1),
            nn.LeakyReLU(0.1, inplace=True), #nn.ReLU(inplace=True),
            # nn.Dropout2d(0.25),
            nn.BatchNorm2d(64),
            nn.MaxPool2d(2,2),
            # 12 x 12 x 64
            nn.Conv2d(64 ,128, kernel_size = 3, stride = 1, padding = 1),
            nn.LeakyReLU(0.1, inplace=True), #nn.ReLU(inplace=True),
            # nn.Dropout2d(0.25),
            nn.BatchNorm2d(128),
            nn.MaxPool2d(2,2),
            # 6 x 6 x 128
            nn.Conv2d(128 ,256, kernel_size = 3, stride = 1, padding = 1),
            nn.LeakyReLU(0.1, inplace=True), #nn.ReLU(inplace=True),
            # nn.Dropout2d(0.25),
            nn.BatchNorm2d(256),
            nn.MaxPool2d(2,2),
            # 3 x 3 x 256
            )
        
        self.flatten = nn.Flatten()
        
        self.muLayer = nn.Linear(3*3*256, latentDim)
        self.sigmaLayer = nn.Linear(3*3*256, latentDim)
        
        self.N = torch.distributions.Normal(0, 1)
        self.N.loc = self.N.loc.cuda() if cuda else self.N.loc 
        self.N.scale = self.N.scale.cuda() if cuda else self.N.scale
        self.kl = 0
        
        self.linearBlocks = nn.Sequential(
            nn.BatchNorm1d(latentDim),
            nn.Linear(latentDim, 1024),
            nn.LeakyReLU(0.1, inplace=True), # nn.ReLU(inplace=True),
            nn.Dropout(0.25),
            nn.BatchNorm1d(1024),
            nn.Linear(1024, 1024),
            nn.LeakyReLU(0.1, inplace=True), #nn.ReLU(inplace=True),
            nn.Dropout(0.25),
            nn.BatchNorm1d(1024),
            nn.Linear(1024, geneNum),
            )
                
        self.activate = nn.Softmax(dim=1)  
  
    def forward(self, img):
        encoder_output = img
        encoder_output = self.convBlocks(encoder_output)
        encoder_output = self.flatten(encoder_output)

        if self.training:
            mu = self.muLayer(encoder_output)
            sigma = torch.exp(self.sigmaLayer(encoder_output))
            self.kl = (sigma**2 + mu**2 - torch.log(sigma+EPSILON) - 1/2).sum()
            decoder_output = mu + sigma*self.N.sample(mu.shape)
        
        else:
            decoder_output = self.muLayer(encoder_output)
            
        decoder_output = self.linearBlocks(decoder_output)
        decoder_output = self.activate(decoder_output)
        
        return decoder_output
    

class Discriminator(nn.Module):
    def __init__(self, geneNum, subtypeNum):
        super(Discriminator, self).__init__()

        self.fcnLayer = nn.Sequential(
            nn.BatchNorm1d(geneNum),
            nn.Linear(geneNum, 1024),
            nn.LeakyReLU(0.1, inplace=True), # nn.ReLU(inplace=True), # nn.LeakyReLU(0.1, inplace=True),
            nn.Dropout(0.25), 
            nn.BatchNorm1d(1024),
            nn.Linear(1024, 1024),
            nn.LeakyReLU(0.1, inplace=True), # nn.ReLU(inplace=True), # nn.LeakyReLU(0.1, inplace=True),
            nn.Dropout(0.25),
            )

        # Output layers
        self.advLayer = nn.Sequential(nn.BatchNorm1d(1024), nn.Linear(1024, 1), nn.Sigmoid())
        self.auxLayer = nn.Sequential(nn.BatchNorm1d(1024), nn.Linear(1024, subtypeNum+1), nn.Softmax(dim=1))
        
    def forward(self, trns):
        output = trns
        output = self.fcnLayer(output)
        validity = self.advLayer(output)
        label = self.auxLayer(output)

        return validity, label


class Classifier(nn.Module):
    def __init__(self, geneNum, subtypeNum):
        super(Classifier, self).__init__()

        self.fcnLayer = nn.Sequential(
            nn.BatchNorm1d(geneNum),
            nn.Linear(geneNum, 1024),
            nn.LeakyReLU(0.1, inplace=True),
            nn.Dropout(0.25), 
            nn.BatchNorm1d(1024),
            nn.Linear(1024, 1024),
            nn.LeakyReLU(0.1, inplace=True),
            nn.Dropout(0.25),
            )

        # Output layers
        self.auxLayer = nn.Sequential(nn.BatchNorm1d(1024), nn.Linear(1024, subtypeNum), nn.Softmax(dim=1))
        
    def forward(self, trns):
        output = trns
        output = self.fcnLayer(output)
        label = self.auxLayer(output)

        return label
      
    