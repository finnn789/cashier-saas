package org.lite.project.cashiersaas.grpc.service;

import com.cashier.grpc.v1.CashierServiceGrpc;
import com.cashier.grpc.v1.GetCatalogItemRequest;
import com.cashier.grpc.v1.GetCatalogItemResponse;
import com.cashier.grpc.v1.GetOrderStatusRequest;
import com.cashier.grpc.v1.GetOrderStatusResponse;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

@Slf4j
@GrpcService
@RequiredArgsConstructor
public class CashierGrpcService extends CashierServiceGrpc.CashierServiceImplBase {

    private final CashierCatalogGrpcHandler catalogHandler;
    private final CashierOrderGrpcHandler orderHandler;

    @Override
    public void getCatalogItem(GetCatalogItemRequest request, StreamObserver<GetCatalogItemResponse> responseObserver) {
        catalogHandler.getCatalogItem(request, responseObserver);
    }

    @Override
    public void getOrderStatus(GetOrderStatusRequest request, StreamObserver<GetOrderStatusResponse> responseObserver) {
        orderHandler.getOrderStatus(request, responseObserver);
    }
}
