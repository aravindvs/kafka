/**
  * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
  * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
  * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
  * License. You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
  * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
  * specific language governing permissions and limitations under the License.
  */
package kafka.api

import java.io.File
import java.util.Collections

import kafka.security.authorizer.AclAuthorizer
import kafka.security.authorizer.AuthorizerUtils.{WildcardHost, WildcardPrincipal}
import kafka.security.auth.{Operation, PermissionType}
import kafka.server.KafkaConfig
import kafka.utils.{CoreUtils, TestUtils}
import org.apache.kafka.common.acl._
import org.apache.kafka.common.acl.AclOperation._
import org.apache.kafka.common.acl.AclPermissionType._
import org.apache.kafka.common.resource.ResourcePattern
import org.apache.kafka.common.resource.PatternType._
import org.apache.kafka.common.resource.ResourceType._
import org.apache.kafka.common.security.auth.{KafkaPrincipal, SecurityProtocol}
import org.apache.kafka.server.authorizer._
import org.junit.Assert

import scala.collection.JavaConverters._

class SslAdminClientIntegrationTest extends SaslSslAdminClientIntegrationTest {
  this.serverConfig.setProperty(KafkaConfig.ZkEnableSecureAclsProp, "true")
  this.serverConfig.setProperty(KafkaConfig.AuthorizerClassNameProp, classOf[AclAuthorizer].getName)

  override protected def securityProtocol = SecurityProtocol.SSL
  override protected lazy val trustStoreFile = Some(File.createTempFile("truststore", ".jks"))

  override def configureSecurityBeforeServersStart(): Unit = {
    val authorizer = CoreUtils.createObject[Authorizer](classOf[AclAuthorizer].getName)
    try {
      authorizer.configure(this.configs.head.originals())
      val ace = new AccessControlEntry(WildcardPrincipal, WildcardHost, ALL, ALLOW)
      authorizer.createAcls(null, List(new AclBinding(new ResourcePattern(TOPIC, "*", LITERAL), ace)).asJava)
      authorizer.createAcls(null, List(new AclBinding(new ResourcePattern(GROUP, "*", LITERAL), ace)).asJava)

      authorizer.createAcls(null, List(clusterAcl(ALLOW, CREATE),
                             clusterAcl(ALLOW, DELETE),
                             clusterAcl(ALLOW, CLUSTER_ACTION),
                             clusterAcl(ALLOW, ALTER_CONFIGS),
                             clusterAcl(ALLOW, ALTER))
        .map(ace => new AclBinding(clusterResourcePattern, ace)).asJava)
    } finally {
      authorizer.close()
    }
  }

  override def setUpSasl(): Unit = {
    startSasl(jaasSections(List.empty, None, ZkSasl))
  }

  override def addClusterAcl(permissionType: PermissionType, operation: Operation): Unit = {
    val ace = clusterAcl(permissionType.toJava, operation.toJava)
    val aclBinding = new AclBinding(clusterResourcePattern, ace)
    val authorizer = servers.head.dataPlaneRequestProcessor.authorizer.get
    val prevAcls = authorizer.acls(new AclBindingFilter(clusterResourcePattern.toFilter, AccessControlEntryFilter.ANY))
      .asScala.map(_.entry).toSet
    authorizer.createAcls(null, Collections.singletonList(aclBinding))
    TestUtils.waitAndVerifyAcls(prevAcls ++ Set(ace), authorizer, clusterResourcePattern)
  }

  override def removeClusterAcl(permissionType: PermissionType, operation: Operation): Unit = {
    val ace = clusterAcl(permissionType.toJava, operation.toJava)
    val authorizer = servers.head.dataPlaneRequestProcessor.authorizer.get
    val clusterFilter = new AclBindingFilter(clusterResourcePattern.toFilter, AccessControlEntryFilter.ANY)
    val prevAcls = authorizer.acls(clusterFilter).asScala.map(_.entry).toSet
    val deleteFilter = new AclBindingFilter(clusterResourcePattern.toFilter, ace.toFilter)
    Assert.assertTrue(authorizer.deleteAcls(null, Collections.singletonList(deleteFilter))
      .get(0).aclBindingDeleteResults().asScala.head.deleted)
    TestUtils.waitAndVerifyAcls(prevAcls -- Set(ace), authorizer, clusterResourcePattern)
  }

  private def clusterAcl(permissionType: AclPermissionType, operation: AclOperation): AccessControlEntry = {
    new AccessControlEntry(new KafkaPrincipal(KafkaPrincipal.USER_TYPE, "*").toString,
      WildcardHost, operation, permissionType)
  }
}
