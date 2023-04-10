'''
Created on Mar 11, 2023

@author: huangch
'''
import torch
import torch.nn as nn
# from torchmetrics import SpearmanCorrCoef, PearsonCorrCoef

cuda = True if torch.cuda.is_available() else False

FloatTensor = torch.cuda.FloatTensor if cuda else torch.FloatTensor
LongTensor = torch.cuda.LongTensor if cuda else torch.LongTensor 
EPSILON = torch.finfo(torch.float32).eps

class PearsonCorrLoss(nn.Module):
    def __init__(self, dim=0, reduction='mean'):
        super(PearsonCorrLoss, self).__init__()
        self.dim = dim
        self.reduction = reduction
        # self.pearson = None # SpearmanCorrCoef(num_outputs=313)
        
    def forward(self, y_pred, y_true):
        
        y_true = y_true.transpose(0, 1) if self.dim == 1 else y_true
        y_pred = y_pred.transpose(0, 1) if self.dim == 1 else y_pred

        y_true_mean = torch.mean(y_true, dim=0)
        y_pred_mean = torch.mean(y_pred, dim=0)

        res_true = y_true - y_true_mean
        res_pred = y_pred - y_pred_mean
        
        cov = torch.mean(res_true * res_pred, dim=0)
        
        var_true = torch.mean(res_true**2, dim=0)
        var_pred = torch.mean(res_pred**2, dim=0)

        
        sigma_true = torch.sqrt(var_true)
        sigma_pred = torch.sqrt(var_pred)
        
        covar = (1 - cov / (EPSILON + sigma_true * sigma_pred))**2

        
        return torch.mean(covar, dim=0) if self.reduction == 'mean' else torch.sum(covar, dim=0)
    
    

# class SpearmanCorrLoss(nn.Module):
#     def __init__(self, dim=0, reduction='mean'):
#         super(SpearmanCorrLoss, self).__init__()
#         self.dim = dim
#         self.reduction = reduction
#         self.spearman = None # SpearmanCorrCoef(num_outputs=313)
#
#     def forward(self, y_pred, y_true):
#
#         y_true = y_true.transpose(0, 1) if self.dim == 1 else y_true
#         y_pred = y_pred.transpose(0, 1) if self.dim == 1 else y_pred
#
#         if self.spearman is None: self.spearman = SpearmanCorrCoef(num_outputs=y_true.shape[1])
#
#
#         spcov = self.spearman(y_pred, y_true)
#         spcov = (1-spcov)**2
#
#         return torch.mean(spcov, dim=0) if self.reduction == 'mean' else torch.sum(spcov, dim=0)
#
#
