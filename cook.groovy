
def cmd = 'git clone git://github.com/nodejs-tw/nodejs-from-scratch.git github/nodejs-from-scratch'

def proc = cmd.execute()

proc.waitFor()

println proc.in.text

cmd = 'sphinx-cook github/nodejs-from-scratch'

proc = cmd.execute()

proc.waitFor()

println proc.in.text
