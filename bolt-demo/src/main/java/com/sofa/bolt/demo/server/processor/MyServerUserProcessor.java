package com.sofa.bolt.demo.server.processor;

import com.alipay.remoting.BizContext;
import com.alipay.remoting.rpc.protocol.SyncUserProcessor;
import com.sofa.bolt.demo.model.MyRequest;
import com.sofa.bolt.demo.model.MyResponse;

/**
 * 自定义的业务逻辑用户处理器
 */
public class MyServerUserProcessor extends SyncUserProcessor<MyRequest> {

    @Override
    public Object handleRequest(BizContext bizCtx, MyRequest request) throws Exception {
        MyResponse response = new MyResponse();
        if (request != null) {
            System.out.println(request);
            response.setResp("from server -> " + request.getReq());
        }
        return response;
    }

    /**
     * 指定感兴趣的请求数据类型，该 UserProcessor 只对感兴趣的请求类型的数据进行处理；
     * 假设 除了需要处理 MyRequest 类型的数据，还要处理 java.lang.String 类型，有两种方式：
     * 1、再提供一个 UserProcessor 实现类，其 interest() 返回 java.lang.String.class.getName()
     * 2、使用 MultiInterestUserProcessor 实现类，可以为一个 UserProcessor 指定 List<String> multiInterest()
     */
    @Override
    public String interest() {
        return MyRequest.class.getName();
    }
}