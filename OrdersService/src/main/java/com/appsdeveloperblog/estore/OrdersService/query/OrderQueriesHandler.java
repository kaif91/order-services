package com.appsdeveloperblog.estore.OrdersService.query;

import org.axonframework.queryhandling.QueryHandler;
import org.springframework.stereotype.Component;

import com.appsdeveloperblog.estore.OrdersService.core.data.OrderEntity;
import com.appsdeveloperblog.estore.OrdersService.core.data.OrdersRepository;
import com.appsdeveloperblog.estore.OrdersService.core.model.OrderSummary;

@Component
public class OrderQueriesHandler {

	OrdersRepository repo;

	public OrderQueriesHandler(OrdersRepository repo) {
		this.repo = repo;
	}

	@QueryHandler()
	public OrderSummary findOrder(FindOrderQuery query) {
		OrderEntity entity = repo.findByOrderId(query.getOrderId());
		OrderSummary summary = new OrderSummary(entity.getOrderId(), entity.getOrderStatus());
		return summary;
	}
}
