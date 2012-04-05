#!/usr/bin/groovy
@GrabConfig(systemClassLoader=true)  
@Grab('com.rabbitmq:amqp-client:2.6.1')
@Grab('net.java.dev.jets3t:jets3t:0.8.1')
@Grab('commons-io:commons-io:2.1')
@Grab('org.eclipse.jgit:org.eclipse.jgit:1.3.0.201202151440-r')
@Grab('org.tmatesoft.svnkit:svnkit:1.3.5')
@Grab('org.tmatesoft.svnkit:svnkit-javahl:1.3.5')

import groovy.json.*

import org.apache.commons.io.*

import com.rabbitmq.client.*

import org.jets3t.service.*
import org.jets3t.service.acl.*
import org.jets3t.service.utils.Mimetypes
import org.jets3t.service.model.S3Object
import org.jets3t.service.security.AWSCredentials
import org.jets3t.service.impl.rest.httpclient.RestS3Service

import org.eclipse.jgit.api.*
import org.eclipse.jgit.transport.*
import org.eclipse.jgit.storage.file.*

import org.tmatesoft.svn.core.*
import org.tmatesoft.svn.core.wc.*
import org.tmatesoft.svn.core.internal.io.*
import org.tmatesoft.svn.core.internal.io.dav.*
import org.tmatesoft.svn.core.internal.io.svn.*

class PublishingWorker {
	
	def rabbitmq
	def aws
	def dropbox

	def version = 1.0
	
	public String toString() {
		"rabbitmq.hostname = ${rabbitmq.hostname}, " +
		"rabbitmq.username = ${rabbitmq.username}, " +
		// "rabbitmq.password = ${rabbitmq.password}, "
		"rabbitmq.port = ${rabbitmq.port}, " +
		"aws.domain = ${aws.domain}"
	}
	
	def routingKey
	def routingKeyBack
	
	def connection
	def channel
	
	/**
	 * 連線到遠端 RabbitMQ 伺服器
	 */
	def connect2rabbitmq() {
		def factory = new ConnectionFactory()

		factory.host = rabbitmq.hostname
		factory.port = rabbitmq.port
		factory.username = rabbitmq.username
		factory.password = rabbitmq.password

		connection = factory.newConnection()
		channel = connection.createChannel()
		
		channel.queueDeclare(routingKey, false, false, false, null)
		channel.queueDeclare(routingKeyBack, false, false, false, null)
	}

	/**
	 * 中斷 RabbitMQ 伺服器連線
	 */
	def disconnect2rabbitmq() {
		channel.close()
		connection.close()
	}
	
	def bucket
	def s3Service
	
	def connect2aws() {
		def awsCredentials = new AWSCredentials(aws.accessKey, aws.secretKey)
		s3Service = new RestS3Service(awsCredentials)
		bucket = s3Service.getBucket(aws.bucketName)
	}
	
	def receive() {
		//Receiving
		def consumer = new QueueingConsumer(channel)
		channel.basicConsume(routingKey, true, consumer)
		
		def slurper = new JsonSlurper()
		
		println " [o] Receiving ..."

		def delivery = consumer.nextDelivery()
		def message = new String(delivery.body)
		println(" [x] Received '$message'")
			
		def msg = slurper.parseText(message)
			
		if (msg.id && msg.version && msg.version <= version) {
			def result = null
			def pathPrefix = 'book/'

			switch (msg?.type) {

				case 'EMBED':
					result = cookEMBED(msg.name, msg.url)
				break
				
				case 'DROPBOX':
					result = cookDROPBOX(msg.name, msg.url)
				break

				case 'GIT':
					result = cookGIT(msg.name, msg.url)
				break
				
				case 'SVN':
					result = cookSVN(msg.name, msg.url)
				break

				case 'SANDBOX':
					result = cookEMBED(msg.name, msg.url)
					pathPrefix = 'sandbox/'
				break

				default:
					println "ignore ${msg?.type}"
			}
				
			if (result) {
				def pathOfPdf = lookupFile("cache/cook/${msg.name}/cook", ~/.*\.pdf/)
				def pathOfEpub = lookupFile("cache/cook/${msg.name}/cook", ~/.*\.epub/)
				def pathOfMobi = lookupFile("cache/cook/${msg.name}/cook", ~/.*\.mobi/)
				def pathOfHtml = lookupFile("cache/cook/${msg.name}/cook", ~/.*\.zip/)
				def pathOfLog = lookupFile("cache/cook/${msg.name}/cook", ~/.*\.log/)

				//upload zip first for author preview
				if (pathOfHtml) {
					upload("${pathPrefix}${msg.name}.zip", pathOfHtml.bytes, 'application/zip')
				}
				//call url
				if (msg.vhost) {
					try {
						def response = new URL(msg.vhost).text
						println response
					}
					catch (ex) {
						println "generate vhost for ${msg.vhost} error"
					}
				}

				if (pathOfPdf) {
					upload("${pathPrefix}${msg.name}.pdf", pathOfPdf.bytes, 'application/pdf')
				}
				if (pathOfEpub) {
					upload("${pathPrefix}${msg.name}.epub", pathOfEpub.bytes, 'application/epub+zip')
				}
				if (pathOfMobi) {
					upload("${pathPrefix}${msg.name}.mobi", pathOfMobi.bytes, 'application/x-mobipocket-ebook')
				}
				// Tell the server job done
				def json = new JsonBuilder()
				json id: msg.id, type: msg.type
				// Sending
				channel.basicPublish('', routingKeyBack, null, json?.toString().bytes)
				println(" [x] Sent '${json}'")

				if (pathOfLog) {
					upload("${pathPrefix}${msg.name}.log", pathOfLog.bytes, 'text/plain')
				}

				// Extract zip to public dir
				if (pathOfHtml) {
					extract("${msg.name}", pathOfHtml)
				}
			}
		}
	}
	
	def upload(key, bytes, ctype, isPublic = false) {
		println "upload ${key} ${ctype}"
		
		// reconnect to aws for prevent timeout
		connect2aws()
		
		def object //S3Object
		
		object = new S3Object(key, bytes)
		object.contentType = ctype

		if (isPublic) {
			def bucketAcl = s3Service.getBucketAcl(bucket)
			bucketAcl.grantPermission(GroupGrantee.ALL_USERS, Permission.PERMISSION_READ);
			object.setAcl(bucketAcl)
		}

		object = s3Service.putObject(bucket, object)
	}
	
	def getContentType(filename) {
		Mimetypes.instance.getMimetype(filename)
	}

	def extract(prefix, file) {
		println "extract ${file.name} to ${prefix}"
		def zipFile = new java.util.zip.ZipFile(file)
		zipFile.entries().findAll { !it.directory }.each {
			//println zipFile.getInputStream(it)
			upload("public/${prefix}/${it.name}", IOUtils.toByteArray(zipFile.getInputStream(it)), getContentType(it.name), true)
		}
	}

	def lookupFile(path, pattern) {
		def result = null
		new File(path).eachFileMatch(pattern) {
			f ->
			if (f.name != 'cover.pdf') {
				result = f
			}
		}
		result
	}
	
	def runCmd(cmd) {
		println "run: ${cmd}"
		def proc = cmd.execute()
		proc.waitFor()
		//println proc.in.text
	}
	
	def cookEMBED(name, url) {
		
		println "Cooking[EMBED] '${name}', '${url}' ..."
	
		try {
			runCmd("rm -rf cache/cook/${name}")
	
			new File("cache/cook/${name}").mkdirs()
			new File("cache/cook/${name}/index.rst").write(new URL(url+'?index').text, 'UTF-8')
			new File("cache/cook/${name}/contents.rst").write(new URL(url).text, 'UTF-8')
	
			runCmd("sphinx-cook cache/cook/${name}")
		}
		catch (e) {
			e.printStackTrace()
			return false
		}
		true
	}

	def cookDROPBOX(name, url) {
		println "Cooking[DROPBOX] '${name}', '${url}' ..."
		
		runCmd("rm -rf cache/cook/${name}")
		runCmd("cp -R -f ${dropbox.location}/${url} cache/cook/${name}")		
		runCmd("sphinx-cook cache/cook/${name}")
	
		true
	}

	def cookSVN(name, url) {
		println "Cooking[SVN] '${name}', '${url}' ..."
		
		if (url==null || url=='' || url=='null') {
			println "invalid url ${url}"
			return false
		}

		def ant = new AntBuilder()
		
		def repodir = new File("cache/svn/${name}")
		def cookdir = new File("cache/cook/${name}")

		def client = new SVNUpdateClient(SVNWCUtil.createDefaultAuthenticationManager("guest", "guest"), SVNWCUtil.createDefaultOptions(true))

		if (repodir.exists()) {
			if (new File(repodir, '.svn').exists()) {
				// SVN update
				client.doUpdate(repodir, SVNRevision.HEAD, true)
			}
			else {
				ant.delete(dir: repodir, failonerror: false)
			}
		}

		if (!repodir.exists()) {
			// SVN Checkout
			repodir.mkdirs()
			client.doCheckout(SVNURL.parseURIDecoded(url) , repodir, SVNRevision.HEAD, SVNRevision.HEAD, SVNDepth.INFINITY, true)
		}
		
		if (cookdir.exists()) {
			ant.delete(dir: cookdir, failonerror: false)
		}
		if (!cookdir.exists()) {
			cookdir.mkdirs()
		}
		ant.copy(todir: cookdir) {
			fileset(dir: repodir)
		}

		runCmd("sphinx-cook -f cover,pdf,epub,mobi,html cache/cook/${name}")
	
		true
	}

	def cookGIT(name, url) {
		println "Cooking[GIT] '${name}', '${url}' ..."
		
		if (url==null || url=='' || url=='null') {
			println "invalid url ${url}"
			return false
		}

		def ant = new AntBuilder()
		
		def repodir = new File("cache/git/${name}")
		def cookdir = new File("cache/cook/${name}")

		if (repodir.exists()) {
			if (new File(repodir, '.git').exists()) {
				// Git Pull first
				new PullCommand(Git.open(repodir).repository).call()
			}
			else {
				ant.delete(dir: repodir, failonerror: false)
			}
		}

		if (!repodir.exists()) {
			// Git Clone
			repodir.mkdirs()
			//def provider = new UsernamePasswordCredentialsProvider("user@mail", "password")
			//new CloneCommand().setURI(url).setDirectory(repodir).setCredentialsProvider(provider).call()
			new CloneCommand().setURI(url).setDirectory(repodir).call()
		}
		
		if (cookdir.exists()) {
			ant.delete(dir: cookdir, failonerror: false)
		}
		if (!cookdir.exists()) {
			cookdir.mkdirs()
		}
		ant.copy(todir: cookdir) {
			fileset(dir: repodir)
		}
		runCmd("sphinx-cook -f cover,pdf,epub,mobi,html cache/cook/${name}")
	
		true
	}
}

// 讀入外部設定檔
def config
def confFile = new File('config.groovy')
def confSecureFile = new File('config-secure.groovy')

// Use secure configuration instead.
if (confSecureFile.exists()) {
	config = new ConfigSlurper().parse(confSecureFile.toURL())
}
else if (confFile.exists()) {
	config = new ConfigSlurper().parse(confFile.toURL())
}

def cook = new PublishingWorker(
	rabbitmq: config.rabbitmq,
	aws: config.aws,
	dropbox: config.dropbox,
	routingKey: config.routing.key.main,
	routingKeyBack: config.routing.key.back
)
//println cook

DAVRepositoryFactory.setup()
SVNRepositoryFactoryImpl.setup()

while (true) {
	try {
		cook.connect2rabbitmq()
		cook.receive()
		cook.disconnect2rabbitmq()
	}
	catch (ConnectException ex) {
		println "Error: ${ex.message}"
	}
}
