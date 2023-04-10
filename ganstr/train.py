from kernels import cell, spot
import argparse

parser = argparse.ArgumentParser()
parser.add_argument('type', default='cell', type=str, help='operation type, cell (xenium) or spot (visium)')
parser.add_argument('-i','--image_folder', help='image folder', required=True)
parser.add_argument('-t','--transcript_file', help='transcript file', required=True)
parser.add_argument('-r','--reference_file', default="", help='reference file')
parser.add_argument('-o','--output_folder', help='output folder', required=True)
parser.add_argument("-ne", "--n_epochs", type=int, default=100, help="number of epochs of training")
parser.add_argument("-bz", "--batch_size", type=int, default=64, help="size of the batches")
parser.add_argument("-lr", "--learning_rate", type=float, default=0.00001, help="adam: learning rate")
parser.add_argument("-b1", "--beta_1", type=float, default=0.5, help="adam: decay of 1st order momentum of gradient")
parser.add_argument("-b2", "--beta_2", type=float, default=0.999, help="adam: decay of 2nd order momentum of gradient")
parser.add_argument("-ld", "--latent_dim", type=int, default=64, help="dimensionality of the latent space")
parser.add_argument("-iz", "--image_size", type=int, default=48, help="input image size")
parser.add_argument('-md','--model', type=str, default="vqvae", help="model, ae/vae/vqvae")
parser.add_argument("-mp", "--model_prefix", type=str, default="", help="model file path prefix")
parser.add_argument("-wd", "--discrimination_weight", type=float, default=1, help="discriminative weight")
parser.add_argument("-wa", "--adversarial_weight", type=float, default=1, help="adversarial weight")
parser.add_argument("-wr", "--reconstruction_weight", type=float, default=1000, help="reconstruction weight")
parser.add_argument("-wc", "--classification_weight", type=float, default=1, help="classification weight")
parser.add_argument("-wxc", "--cell_correlation_weight", type=float, default=1, help="correlation weight")
parser.add_argument("-wxg", "--gene_correlation_weight", type=float, default=1000, help="correlation weight")
parser.add_argument("-wkl", "--kl_divergence_weight", type=float, default=1, help="KL divergence weight")
parser.add_argument("-wvq", "--vq_loss_weight", type=float, default=1, help="VQ loss weight")
parser.add_argument("-cr", "--checkpoint_pass_rate", type=float, default=0.0, help="checkpoint pass rate")
parser.add_argument("-is", "--saving_interval", type=int, default=0, help="interval between model saving")
parser.add_argument("-it", "--test_interval", type=int, default=0, help="interval between model testing")
parser.add_argument("-ft", "--final_test", action='store_true', help="final test")
parser.add_argument("-tb", "--tensorboard", action='store_true', help="tensorboard")
parser.add_argument("-tp", "--tensorboard_port", type=str, default="6006", help="tensorboard port")
parser.add_argument("-gp", "--generate_progress", action='store_true', help="generate progress")
parser.add_argument("-gg", "--generate_graphs", action='store_true', help="generate graphs")
parser.add_argument("-gr", "--generate_report", action='store_true', help="generate report")

opt = parser.parse_args()
print(opt)

if opt.type == 'cell' or opt.type == 'xenium':
    cell.train(opt)
elif opt.type == 'spot' or opt.type == 'visium': 
    spot.train(opt)
else:
    raise Exception("Wrong operation type")





