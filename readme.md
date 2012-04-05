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
sudo apt-get install git-core

# docutils
sudo apt-get install python-docutils

# mercurial
sudo apt-get install mercurial

# sphinx (contpub branch)
hg clone https://bitbucket.org/lyhcode/sphinx
cd sphinx
sudo easy_install .

# pygments
hg clone https://bitbucket.org/birkenfeld/pygments-main
cd pygments-main
sudo easy_install .

# s3cmd
sudo apt-get install s3cmd

# download fonts
s3cmd sync s3://s3.contpub.org/fonts/ fonts
sudo cp -rf fonts /usr/share/fonts/contpub
fc-cache

# sphinx-cook
git clone git@github.com:contpub/sphinx-cook.git
sudo ln -sf ~/sphinx-cook/bin/sphinx-cook /usr/local/bin/sphinx-cook

# CaiLun
git clone git@github.com:contpub/CaiLun.git
cd CaoLun
cp config.groovy config-secure.groovy
# modify config-secure.groovy

```


