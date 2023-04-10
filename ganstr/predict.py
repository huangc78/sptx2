from kernels import cell, spot
import argparse

parser = argparse.ArgumentParser()
parser.add_argument('type', default='cell', type=str, help='operation type, cell (xenium) or spot (visium)')
parser.add_argument('-i','--image_folder', help='image folder', required=True)
parser.add_argument('-m', '--model_file', help='model_file', required=True)
parser.add_argument('-o','--output_file', help='output file', required=True)

opt = parser.parse_args()
print(opt)

if opt.type == 'cell' or opt.type == 'xenium':
    cell.predict(opt)
elif opt.type == 'spot' or opt.type == 'visium': 
    spot.predict(opt)
else:
    raise Exception("Wrong operation type")

