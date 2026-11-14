package org.lite.project.cashiersaas.grpc.service;

import com.cashier.grpc.v1.CashierServiceGrpc;
import com.cashier.grpc.v1.ConsumeAiQuestionRequest;
import com.cashier.grpc.v1.ConsumeAiQuestionResponse;
import com.cashier.grpc.v1.GetAnomaliesRequest;
import com.cashier.grpc.v1.GetAnomaliesResponse;
import com.cashier.grpc.v1.GetCatalogItemRequest;
import com.cashier.grpc.v1.GetCatalogItemResponse;
import com.cashier.grpc.v1.GetOrderStatusRequest;
import com.cashier.grpc.v1.GetOrderStatusResponse;
import com.cashier.grpc.v1.GetRestockSuggestionsRequest;
import com.cashier.grpc.v1.GetRestockSuggestionsResponse;
import com.cashier.grpc.v1.GetSalesSummaryRequest;
import com.cashier.grpc.v1.GetSalesSummaryResponse;
import com.cashier.grpc.v1.RecordAiUsageRequest;
import com.cashier.grpc.v1.RecordAiUsageResponse;
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
    private final CashierInsightGrpcHandler insightHandler;
    private final CashierAiUsageGrpcHandler aiUsageHandler;

    @Override
    public void getCatalogItem(GetCatalogItemRequest request, StreamObserver<GetCatalogItemResponse> responseObserver) {
        catalogHandler.getCatalogItem(request, responseObserver);
    }

    @Override
    public void getOrderStatus(GetOrderStatusRequest request, StreamObserver<GetOrderStatusResponse> responseObserver) {
        orderHandler.getOrderStatus(request, responseObserver);
    }

    @Override
    public void getSalesSummary(GetSalesSummaryRequest request, StreamObserver<GetSalesSummaryResponse> responseObserver) {
        insightHandler.getSalesSummary(request, responseObserver);
    }

    @Override
    public void getRestockSuggestions(GetRestockSuggestionsRequest request, StreamObserver<GetRestockSuggestionsResponse> responseObserver) {
        insightHandler.getRestockSuggestions(request, responseObserver);
    }

    @Override
    public void getAnomalies(GetAnomaliesRequest request, StreamObserver<GetAnomaliesResponse> responseObserver) {
        insightHandler.getAnomalies(request, responseObserver);
    }

    @Override
    public void consumeAiQuestion(ConsumeAiQuestionRequest request, StreamObserver<ConsumeAiQuestionResponse> responseObserver) {
        aiUsageHandler.consumeAiQuestion(request, responseObserver);
    }

    @Override
    public void recordAiUsage(RecordAiUsageRequest request, StreamObserver<RecordAiUsageResponse> responseObserver) {
        aiUsageHandler.recordAiUsage(request, responseObserver);
    }
}
