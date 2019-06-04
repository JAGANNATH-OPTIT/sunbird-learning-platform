package org.ekstep.actor

import org.ekstep.actor.core.BaseAPIActor
import org.ekstep.commons.{APIIds, Request}
import akka.dispatch.Futures
import akka.pattern.Patterns
import org.ekstep.mgr.impl.ContentManagerImpl

object ContentActor extends BaseAPIActor {


  override def onReceive(request: Request) = {

    request.apiId match {
      case APIIds.READ_CONTENT =>
        readContent(request)

      case APIIds.CREATE_CONTENT =>
        createContent(request)

      case APIIds.UPDATE_CONTENT =>
        updateContent(request)

      case APIIds.REVIEW_CONTENT =>
        reviewContent(request)

      case APIIds.UPLOAD_CONTENT =>
        uploadContent(request)

      case APIIds.PUBLISH_PUBLIC_CONTENT =>
        publishContent(request, "public")

      case APIIds.PUBLISH_UNLISTED_CONTENT =>
        publishContent(request, "unlisted")

      case _ =>
        invalidAPIResponseSerialized(request.apiId);
    }

  }


  private def readContent(request: Request) = {
    val readContentMgr = new ContentManagerImpl()
    val result = readContentMgr.read(request)

    val response = OK(request.apiId, result)
    Patterns.pipe(Futures.successful(response), getContext().dispatcher).to(sender())
  }

  private def createContent(request: Request) = {
    val contentMgr = new ContentManagerImpl()
    val result = contentMgr.create(request)

    val response = OK(request.apiId, result)
    Patterns.pipe(Futures.successful(response), getContext().dispatcher).to(sender())
  }

  private def updateContent(request: Request) = {
    val contentMgr = new ContentManagerImpl()
    val result = contentMgr.update(request)

    val response = OK(request.apiId, result)
    Patterns.pipe(Futures.successful(response), getContext().dispatcher).to(sender())
  }

  private def reviewContent(request: Request) = {
    val contentMgr = new ContentManagerImpl()
    val result = contentMgr.review(request)

    val response = OK(request.apiId, result)
    Patterns.pipe(Futures.successful(response), getContext().dispatcher).to(sender())
  }

  private def uploadContent(request: Request) = {
    val contentMgr = new ContentManagerImpl()
    val fileUrl = request.params.getOrElse("fileUrl","")
    if(fileUrl != None){

      val result = contentMgr.uploadUrl(request)

      val response = OK(request.apiId, result)
      Patterns.pipe(Futures.successful(response), getContext().dispatcher).to(sender())
    }


  }

  private def publishContent(request: Request, publishType: String) = {
    val contentMgr = new ContentManagerImpl()
    val result = contentMgr.publishByType(request, publishType)

    val response = OK(request.apiId, result)
    Patterns.pipe(Futures.successful(response), getContext().dispatcher).to(sender())
  }

}
