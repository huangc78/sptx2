#! /bin/bash
source /opt/anaconda3/etc/profile.d/conda.sh
conda activate st2

export RSTUDIO_WHICH_R=/opt/anaconda3/envs/st2/bin/R
# export RSTUDIO_CHROMIUM_ARGUMENTS="--disable-gpu"
# export LD_LIBRARY_PATH=/opt/anaconda3/envs/st2/lib:$LD_LIBRARY_PATH

# export PKG_CONFIG_PATH=/usr/lib/x86_64-linux-gnu/pkgconfig 
export LIBRARY_PATH=/usr/lib/x86_64-linux-gnu 
# export INCLUDE_PATH=/usr/include/x86_64-linux-gnu 
# LD_LIBRARY_PATH=/opt/anaconda3/envs/st2/lib:$LD_LIBRARY_PATH 
/usr/bin/rstudio $1
