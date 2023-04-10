#! /bin/sh
conda deactivate
conda env remove -n st2
conda create -n st2 python=3.8 jupyterlab tqdm ipywidgets rpy2 r-essentials r-base r-irkernel openslide -y
conda install -n st2 -c "nvidia/label/cuda-11.8.0" cuda-toolkit -y

. /opt/anaconda3/etc/profile.d/conda.sh
conda activate st2

jupyter nbextension enable --py widgetsnbextension

pip install pandas scipy seaborn scanpy squidpy scikit-learn-intelex scikit-misc goatools openslide-python pyreadr

# install pytorch
pip3 install torch torchvision --index-url https://download.pytorch.org/whl/cu118
pip install torchviz

# install tensorflow
pip install nvidia-cudnn-cu11==8.6.0.163 tensorflow==2.12.*
pip install --upgrade "jax[cuda]" -f https://storage.googleapis.com/jax-releases/jax_cuda_releases.html
pip install protobuf==3.20.3 --force

#install other DL tools
pip install tensorboard 

# install testing package
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
pip install python-bioformats
pip install pyvips
pip install tifffile
