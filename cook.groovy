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
	
	def version = 1.0
	
	public String toString() {
		"rabbitmq.hostname = ${rabbitmq.hostname}, " +
		"rabbitmq.username = ${rabbitmq.username}, " +
		// "rabbitmq.password = ${rabbitmq.password}, "
		"rabbitmq.port = ${rabbitmq.port}, " +
		"aws.domain = ${aws.domain}"
	}
	
	def routingKey = 'RepoCook'
	def routingKeyBack = 'CookBack'
	
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
		
		while (true) {
			println "Receiving ..."
			def delivery = consumer.nextDelivery()
			def message = new String(delivery.body)
			println(" [x] Received '$message'")
			
			def msg = slurper.parseText(message)
			
			if (msg.version && msg.version <= version) {
				if (msg.type.equals('GIT')) {
					def result = cookGIT(msg.name, msg.url)
					
					def json = new JsonBuilder()
					json id: msg.id, pdf: result.pdf, epub: result.epub
					
					//Sending
					channel.basicPublish('', routingKeyBack, null, json?.toString().bytes)
					println(" [x] Sent '${json}'")
				}
			}
		}
	}
	
	def cookGIT(name, url) {
		println "Cooking '${name}', '${url}' ..."
		def cmd = "git clone ${url} cache/${name}"
		def proc = cmd.execute()
		proc.waitFor()
		println proc.in.text
		cmd = "sphinx-cook cache/${name}"
		proc = cmd.execute()
		proc.waitFor()
		println proc.in.text
		
		def pathOfPdf = null
		def pathOfEpub = null
		
		new File("cache/${name}/cook").eachFileMatch(~/.*\.pdf/) {
			f ->
			pathOfPdf = f
		}
		
		new File("cache/${name}/cook").eachFileMatch(~/.*\.epub/) {
			f ->
			pathOfEpub = f
		}
		
		println "pdf file: ${pathOfPdf}"
		println "epub file: ${pathOfEpub}"
		
		def object
		
		object = new S3Object("${name}.pdf", pathOfPdf.bytes)
		object = s3Service.putObject(bucket, object)
		
		object = new S3Object("${name}.epub", pathOfEpub.bytes)
		object = s3Service.putObject(bucket, object)
		
		println object
		
		/*
		cmd = "s3cmd put -P ${pathOfPdf} s3://contpub/cache/${name}.pdf"
		proc = cmd.execute()
		proc.waitFor()
		println proc.in.text
		*/
		
		[
			pdf: "http://contpub.s3.amazonaws.com/${name}.pdf",
			epub: "http://contpub.s3.amazonaws.com/${name}.epub"
		]
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

def cook = new RepoCook()
cook.rabbitmq = config.rabbitmq
cook.aws = config.aws

//println cook

cook.connect2rabbitmq()
cook.connect2aws()

//println cook.bucket
//println cook.s3Service.listObjects(cook.bucket)

cook.receive()
cook.disconnect2rabbitmq()

