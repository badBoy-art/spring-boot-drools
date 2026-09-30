package com.example.drools.worker;

public final class WorkerNotOwnerException extends RuntimeException {
  private final String ownerNode;
  private final String ownerUrl;

  public WorkerNotOwnerException(String ownerNode, String ownerUrl) {
    super("KieSession is owned by another worker");
    this.ownerNode = ownerNode;
    this.ownerUrl = ownerUrl;
  }

  public String getOwnerNode() {
    return ownerNode;
  }

  public String getOwnerUrl() {
    return ownerUrl;
  }
}
