CaiLun: Distributed repo cook for SimaQian
===========================================

Install & configuration

1. Install RabbitMQ
2. Copy cook.properties to cook-secure.properties
3. Modify cook-secure.properties with correct passwords/settings

Post-configuration

1. sudo easy_install -U Sphinx
2. patch Sphinx (gist)
3. sudo easy_install Pygments
4. hg clone https://bitbucket.org/birkenfeld/pygments-main
5. cd pygments-main
6. sudo easy_install .

Execute

1. ./cook.groovy

