CaiLun: Distributed repo cook for SimaQian
===========================================

### Installation

```
# TeX Live
sudo apt-get install texlive-full

# apt-add-repository
sudo apt-get install python-software-properties
sudo apt-add-repository ppa:groovy-dev/groovy
sudo apt-get update

# groovy
sudo apt-get install groovy

# python-pip
sudo apt-get install python-pip

# git
# sudo apt-get install git-core
sudo apt-get install git

# docutils
sudo apt-get install python-docutils

# mercurial
sudo apt-get install mercurial

# sphinx (contpub branch)
hg clone https://bitbucket.org/lyhcode/sphinx
cd sphinx
sudo easy_install .

# pygments
# hg clone https://bitbucket.org/birkenfeld/pygments-main
# cd pygments-main
# sudo easy_install .
sudo apt-get install python-pygments

# s3cmd
sudo apt-get install s3cmd

# download fonts
s3cmd sync s3://s3.contpub.org/fonts/ fonts
sudo cp -rf fonts /usr/share/fonts/contpub
fc-cache

# ImageMagick
sudo apt-get install imagemagick

# KindleGen
wget http://s3.amazonaws.com/kindlegen/kindlegen_linux_2.6_i386_v2_7.tar.gz
mkdir -p kindlegen
cd kindlegen
tar zxvf ../kindlegen_linux_2.6_i386_v2_4.tar.gz
sudo mv kindlegen /usr/local/bin

# pdftk
sudo apt-get install pdftk

# sphinx-cook
git clone git@github.com:contpub/sphinx-cook.git
sudo ln -sf ~/sphinx-cook/bin/sphinx-cook /usr/local/bin/sphinx-cook

# CaiLun
git clone git@github.com:contpub/CaiLun.git
cd CaoLun
cp config.groovy config-secure.groovy
# modify config-secure.groovy
```

### fix can't write on file `xxxxx.pdf'

```
sudo dd if=/dev/zero of=/var/512mb.swap bs=1M count=512
sudo mkswap /var/512mb.swap 
sudo swapon /var/512mb.swap 
```
