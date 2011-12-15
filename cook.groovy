#!/usr/bin/groovy
@Grab('com.rabbitmq:amqp-client:2.6.1')
@Grab('net.java.dev.jets3t:jets3t:0.8.1')

import groovy.json.*
import com.rabbitmq.client.*
import org.jets3t.service.*
import org.jets3t.service.model.S3Object
import org.jets3t.service.security.AWSCredentials
import org.jets3t.service.impl.rest.httpclient.RestS3Service

class RepoCook {
	
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
			
		if (msg.version && msg.version <= version) {
			def result = null
				
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

				default:
					println "ignore ${msg?.type}"
			}
				
			if (result) {
				def pathOfPdf = lookupFile("cache/${msg.name}/cook", ~/.*\.pdf/)
				def pathOfEpub = lookupFile("cache/${msg.name}/cook", ~/.*\.epub/)

				if (pathOfPdf) {
					upload("${msg.name}.pdf", pathOfPdf.bytes, 'application/pdf')
				}
				if (pathOfEpub) {
					upload("${msg.name}.epub", pathOfEpub.bytes, 'application/epub+zip')
				}

				def json = new JsonBuilder()
				json id: msg.id
			
				//Sending
				channel.basicPublish('', routingKeyBack, null, json?.toString().bytes)
				println(" [x] Sent '${json}'")
			}
		}
	}
	
	def upload(key, bytes, ctype) {
		println "upload ${key}"
		
		// reconnect to aws for prevent timeout
		connect2aws()
		
		def object //S3Object
		
		object = new S3Object(key, bytes)
		object.contentType = ctype
		object = s3Service.putObject(bucket, object)
	}
	
	def lookupFile(path, pattern) {
		def result = null
		new File(path).eachFileMatch(pattern) {
			f ->
			result = f
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
		
		runCmd("rm -rf cache/${name}")

		new File("cache/${name}").mkdirs()
		new File("cache/${name}/index.rst").write(new URL(url+'?index').text, 'UTF-8')
		new File("cache/${name}/contents.rst").write(new URL(url).text, 'UTF-8')

		runCmd("sphinx-cook cache/${name}")

		true
	}

	def cookDROPBOX(name, url) {
		println "Cooking[DROPBOX] '${name}', '${url}' ..."
		
		runCmd("rm -rf cache/${name}")
		runCmd("cp -R -f ${dropbox.location}/${url} cache/${name}")		
		runCmd("sphinx-cook cache/${name}")
	
		true
	}

	def cookGIT(name, url) {
		println "Cooking[GIT] '${name}', '${url}' ..."
		
		runCmd("rm -rf cache/${name}")
		runCmd("git clone ${url} cache/${name}")		
		runCmd("sphinx-cook cache/${name}")
	
		true
	}
}

// 讀入外部設定檔
def config
def confFile = new File('cook.properties')
def confSecureFile = new File('cook-secure.properties')

// Use secure configuration instead.
if (confSecureFile.exists()) {
	config = new ConfigSlurper().parse(confSecureFile.toURL())
}
else if (confFile.exists()) {
	config = new ConfigSlurper().parse(confFile.toURL())
}

def cook = new RepoCook(
	rabbitmq: config.rabbitmq,
	aws: config.aws,
	dropbox: config.dropbox,
	routingKey: config.routing.key.main,
	routingKeyBack: config.routing.key.back
)
//println cook

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
