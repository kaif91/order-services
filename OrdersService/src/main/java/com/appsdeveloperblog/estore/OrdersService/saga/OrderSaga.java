package com.appsdeveloperblog.estore.OrdersService.saga;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.axonframework.commandhandling.CommandCallback;
import org.axonframework.commandhandling.CommandMessage;
import org.axonframework.commandhandling.CommandResultMessage;
import org.axonframework.commandhandling.gateway.CommandGateway;
import org.axonframework.deadline.DeadlineManager;
import org.axonframework.deadline.annotation.DeadlineHandler;
import org.axonframework.messaging.responsetypes.ResponseTypes;
import org.axonframework.modelling.saga.EndSaga;
import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.StartSaga;
import org.axonframework.queryhandling.QueryGateway;
import org.axonframework.queryhandling.QueryUpdateEmitter;
import org.axonframework.spring.stereotype.Saga;
import org.springframework.beans.factory.annotation.Autowired;

import com.appsdeveloperblog.estore.OrdersService.command.commands.ApproveOrderCommand;
import com.appsdeveloperblog.estore.OrdersService.command.commands.RejectOrderCommand;
import com.appsdeveloperblog.estore.OrdersService.core.events.OrderApprovedEvent;
import com.appsdeveloperblog.estore.OrdersService.core.events.OrderCreatedEvent;
import com.appsdeveloperblog.estore.OrdersService.core.events.OrderRejectedEvent;
import com.appsdeveloperblog.estore.OrdersService.core.model.OrderStatus;
import com.appsdeveloperblog.estore.OrdersService.core.model.OrderSummary;
import com.appsdeveloperblog.estore.OrdersService.query.FindOrderQuery;
import com.developer.estore.core.command.CancelProductReservationCommand;
import com.developer.estore.core.command.ProcessPaymentCommand;
import com.developer.estore.core.command.ReserveProductCommand;
import com.developer.estore.core.events.PaymentProcessedEvent;
import com.developer.estore.core.events.ProductReservationCanceledEvent;
import com.developer.estore.core.events.ProductReservedEvent;
import com.developer.estore.core.model.User;
import com.developer.estore.core.query.FetchUserPaymentDetailsQuery;

@Saga
public class OrderSaga {

	@Autowired
	private transient CommandGateway commandGateway;
	@Autowired
	private transient QueryGateway queryGateway;
	@Autowired
	private transient DeadlineManager deadlineManger;
	@Autowired
	private transient QueryUpdateEmitter emitter;
	
	private String deadLineId;

	@StartSaga
	@SagaEventHandler(associationProperty = "orderId")
	public void handle(OrderCreatedEvent event) {

		ReserveProductCommand reserveProductCommand = ReserveProductCommand.builder().orderId(event.getOrderId())
				.productId(event.getProductId()).quantity(event.getQuantity()).userId(event.getUserId()).build();
		commandGateway.send(reserveProductCommand, new CommandCallback<ReserveProductCommand, Object>() {

			@Override
			public void onResult(CommandMessage<? extends ReserveProductCommand> commandMessage,
					CommandResultMessage<? extends Object> commandResultMessage) {
				if (commandResultMessage.isExceptional()) {
					// start compensating transaction right away!!!
					RejectOrderCommand command = new RejectOrderCommand(event.getOrderId(), commandResultMessage.exceptionResult().getMessage());
					commandGateway.send(command);
				}
			}
		});
	}

	@SagaEventHandler(associationProperty = "productId")
	public void handle(ProductReservedEvent event) {

		FetchUserPaymentDetailsQuery userPaymentQuery = new FetchUserPaymentDetailsQuery(event.getUserId());
		User user = null;
		try {
			user = queryGateway.query(userPaymentQuery, ResponseTypes.instanceOf(User.class)).join();
		} catch (Exception e) {
			// start compensating transaction
			cancelProductReservation(event, e.getMessage());
		}
		if (user == null) {
			// start compensating transaction
			cancelProductReservation(event, "could not fetch user payment details");
			return;
		}

		// schedule deadline
		deadLineId =deadlineManger.schedule(Duration.of(10, ChronoUnit.SECONDS), "payment-process-deadline", event);

		ProcessPaymentCommand paymentCommand = ProcessPaymentCommand.builder().orderId(event.getOrderId())
				.paymentDetails(user.getPaymentDetails()).paymentId(UUID.randomUUID().toString()).build();
		String result = null;
		try {
			result = commandGateway.sendAndWait(paymentCommand);
		} catch (Exception e) {
			// start compensating transaction
			cancelProductReservation(event, e.getMessage());
			return;
		}

		if (result == null) {
			// start compensating transaction
			cancelProductReservation(event, "could not process payment");
		}
	}

	private void cancelDeadline() {
		if(deadLineId!=null) {
		deadlineManger.cancelSchedule("payment-process-deadline", deadLineId);
		deadLineId=null;
		}
	}

	private void cancelProductReservation(ProductReservedEvent event, String reason) {
		cancelDeadline();
		CancelProductReservationCommand command = CancelProductReservationCommand.builder().orderId(event.getOrderId())
				.productId(event.getProductId()).quantity(event.getQuantity()).userId(event.getUserId()).reason(reason)
				.build();
		commandGateway.send(command);
	}

	@SagaEventHandler(associationProperty = "orderId")
	public void handle(PaymentProcessedEvent paymentProcessedEvent) {
		cancelDeadline();
		ApproveOrderCommand approveOrderCommand = new ApproveOrderCommand(paymentProcessedEvent.getOrderId());
		commandGateway.send(approveOrderCommand);
	}

	@EndSaga
	@SagaEventHandler(associationProperty = "orderId")
	public void handle(OrderApprovedEvent event) {
		// order approved
		// For programmatic end use below code rather than annotation
		// SagaLifecycle.end();
		emitter.emit(FindOrderQuery.class, query->true,
				new OrderSummary(event.getOrderId(),event.getOrderStatus(),""));

	}

	@SagaEventHandler(associationProperty = "orderId")
	public void handle(ProductReservationCanceledEvent event) {
		RejectOrderCommand command = new RejectOrderCommand(event.getOrderId(), event.getReason());
		commandGateway.send(command);
	}

	@EndSaga
	@SagaEventHandler(associationProperty = "orderId")
	public void handle(OrderRejectedEvent event) {
		emitter.emit(FindOrderQuery.class, query->true,
				new OrderSummary(event.getOrderId(),OrderStatus.REJECTED,event.getReason()));
	}

	@DeadlineHandler(deadlineName = "payment-process-deadline")
	public void handleDeadline(ProductReservedEvent event) {
		// compensating transaction
		cancelProductReservation(event, "Payment Timeout");
	}
}
