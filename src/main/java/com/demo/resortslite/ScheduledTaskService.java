package com.demo.resortslite;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Scheduled task service using Azure Service Bus for distributed task scheduling.
 * 
 * FIXED: cr-java-0111 - Replaced java.util.Timer with Azure Service Bus scheduled messages
 */
@Service
public class ScheduledTaskService {

    @Value("${azure.servicebus.connection-string}")
    private String serviceBusConnectionString;

    @Value("${azure.servicebus.queue-name}")
    private String queueName;

    private ServiceBusSenderClient senderClient;

    /**
     * Initializes Azure Service Bus sender client.
     */
    private void initializeServiceBusClient() {
        if (senderClient == null && serviceBusConnectionString != null && !serviceBusConnectionString.isEmpty()) {
            senderClient = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString)
                .sender()
                .queueName(queueName)
                .buildClient();
        }
    }

    /**
     * Schedules a task to be executed at a specific time using Azure Service Bus.
     * This replaces java.util.Timer with a distributed, timezone-agnostic solution.
     * 
     * @param taskName Name of the task
     * @param delaySeconds Delay in seconds before task execution
     * @return Status message
     */
    public String scheduleTask(String taskName, long delaySeconds) {
        try {
            initializeServiceBusClient();
            
            if (senderClient != null) {
                ServiceBusMessage message = new ServiceBusMessage(taskName);
                
                // Schedule message for future delivery (timezone-agnostic)
                Instant scheduledTime = Instant.now().plusSeconds(delaySeconds);
                message.setScheduledEnqueueTime(scheduledTime.atOffset(java.time.ZoneOffset.UTC));
                
                senderClient.sendMessage(message);
                
                return "Task '" + taskName + "' scheduled for execution at " + scheduledTime + " UTC";
            } else {
                return "Azure Service Bus not configured. Task scheduling skipped.";
            }
        } catch (Exception e) {
            return "Failed to schedule task: " + e.getMessage();
        }
    }

    /**
     * Closes the Service Bus sender client.
     */
    public void close() {
        if (senderClient != null) {
            senderClient.close();
        }
    }
}
