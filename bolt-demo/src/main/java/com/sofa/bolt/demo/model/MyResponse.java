package com.sofa.bolt.demo.model;

import java.io.Serializable;

/**
 * 实现Serializable接口
 */
public class MyResponse implements Serializable {

    private static final long serialVersionUID = -6215194863976521002L;

    private String resp = "default resp from server";

    public String getResp() {
        return resp;
    }

    public void setResp(String resp) {
        this.resp = resp;
    }

    @Override
    public String toString() {
        return resp;
    }
}