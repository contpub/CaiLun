@Grab('com.rabbitmq:amqp-client:2.6.1')

import groovy.json.*
import com.rabbitmq.client.*

class RepoCook {
	def routingKey = 'RepoCook'
	def routingKeyBack = 'CookBack'
	
	def host = 'localhost'
	def version = 1.0
	
	def receive() {
		def factory = new ConnectionFactory()
		factory.host = host
		def connection = factory.newConnection()
		def channel = connection.createChannel()
		
		channel.queueDeclare(routingKey, false, false, false, null)
		channel.queueDeclare(routingKeyBack, false, false, false, null)

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
				if (msg.type.equals('GitHub')) {
					def result = cookGitHub(msg.name, msg.url)
					
					def json = new JsonBuilder()
					json id: msg.id, pdf: result.pdf, epub: result.epub
					
					//Sending
					channel.basicPublish('', routingKeyBack, null, json?.toString().bytes)
					println(" [x] Sent '${json}'")
				}
			}
		}
		
		channel.close()
		connection.close()
	}
	
	def cookGitHub(name, url) {
		println "Cooking '${name}', '${url}' ..."
		def cmd = "git clone ${url} GitHub/${name}"
		def proc = cmd.execute()
		proc.waitFor()
		println proc.in.text
		cmd = "sphinx-cook GitHub/${name}"
		proc = cmd.execute()
		proc.waitFor()
		println proc.in.text
		
		def pathOfPdf = null
		def pathOfEpub = null
		
		new File("GitHub/${name}/cook").eachFileMatch(~/.*\.pdf/) {
			f ->
			pathOfPdf = f
		}
		
		new File("GitHub/${name}/cook").eachFileMatch(~/.*\.epub/) {
			f ->
			pathOfEpub = f
		}
		
		println "pdf file: ${pathOfPdf}"
		println "epub file: ${pathOfEpub}"
		
		cmd = "s3cmd put -P ${pathOfPdf} s3://contpub/GitHub/${name}.pdf"
		proc = cmd.execute()
		proc.waitFor()
		println proc.in.text
		
		cmd = "s3cmd put -P ${pathOfEpub} s3://contpub/GitHub/${name}.epub"
		proc = cmd.execute()
		proc.waitFor()
		println proc.in.text
		
		[
			pdf: "http://contpub.s3.amazonaws.com/GitHub/${name}.pdf",
			epub: "http://contpub.s3.amazonaws.com/GitHub/${name}.epub"
		]
	}
}

new RepoCook().receive()

