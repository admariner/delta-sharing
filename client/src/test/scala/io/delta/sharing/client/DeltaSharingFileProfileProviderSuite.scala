/*
 * Copyright (2021) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.delta.sharing.client

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

import org.apache.commons.io.FileUtils
import org.apache.hadoop.conf.Configuration
import org.apache.spark.SparkFunSuite

class DeltaSharingFileProfileProviderSuite extends SparkFunSuite {

  private def testProfile(profile: String, expected: DeltaSharingProfile): Unit = {
    val temp = Files.createTempFile("test", ".share").toFile
    try {
      FileUtils.writeStringToFile(temp, profile, UTF_8)
      assert(new DeltaSharingFileProfileProvider(new Configuration, temp.getCanonicalPath)
        .getProfile == expected)
    } finally {
      temp.delete()
    }
  }

  test("parse") {
    testProfile(
      """{
        |  "shareCredentialsVersion": 1,
        |  "endpoint": "foo",
        |  "bearerToken": "bar",
        |  "expirationTime": "2021-11-12T00:12:29.0Z"
        |}
        |""".stripMargin,
      DeltaSharingProfile(
        shareCredentialsVersion = Some(1),
        endpoint = "foo",
        bearerToken = "bar",
        expirationTime = "2021-11-12T00:12:29.0Z"
      )
    )
  }

  test("expirationTime is optional") {
    testProfile(
      """{
        |  "shareCredentialsVersion": 1,
        |  "endpoint": "foo",
        |  "bearerToken": "bar"
        |}
        |""".stripMargin,
      DeltaSharingProfile(
        shareCredentialsVersion = Some(1),
        endpoint = "foo",
        bearerToken = "bar"
      )
    )
  }

  test("shareCredentialsVersion is missing") {
    val e = intercept[IllegalArgumentException] {
      testProfile(
        """{
          |  "endpoint": "foo",
          |  "bearerToken": "bar"
          |}
          |""".stripMargin,
        null
      )
    }
    assert(e.getMessage.contains(
      "Cannot find the 'shareCredentialsVersion' field in the profile file"))
  }

  test("shareCredentialsVersion is incorrect") {
    val e = intercept[IllegalArgumentException] {
      testProfile(
        """{
          |  "shareCredentialsVersion" : 2,
          |  "endpoint": "foo",
          |  "bearerToken": "bar"
          |}
          |""".stripMargin,
        null
      )
    }
    assert(e.getMessage.contains(
      "bearer_token only supports version 1"))
  }

  test("shareCredentialsVersion is not supported") {
    val e = intercept[IllegalArgumentException] {
      testProfile(
        """{
          |  "shareCredentialsVersion": 100
          |}
          |""".stripMargin,
        null
      )
    }
    assert(e.getMessage.contains(
      "'shareCredentialsVersion' in the profile is 100 which is too new."))
  }

  test("endpoint is missing") {
    val e = intercept[IllegalArgumentException] {
      testProfile(
        """{
          |  "shareCredentialsVersion": 1,
          |  "bearerToken": "bar"
          |}
          |""".stripMargin,
        null
      )
    }
    assert(e.getMessage.contains("Cannot find the 'endpoint' field in the profile file"))
  }

  test("bearerToken is missing") {
    val e = intercept[IllegalArgumentException] {
      testProfile(
        """{
          |  "shareCredentialsVersion": 1,
          |  "endpoint": "foo"
          |}
          |""".stripMargin,
        null
      )
    }
    assert(e.getMessage.contains("Cannot find the 'bearerToken' field in the profile file"))
  }

  test("unknown field should be ignored") {
    testProfile(
      """{
        |  "shareCredentialsVersion": 1,
        |  "endpoint": "foo",
        |  "bearerToken": "bar",
        |  "expirationTime": "2021-11-12T00:12:29.0Z",
        |  "futureField": "xyz"
        |}
        |""".stripMargin,
      DeltaSharingProfile(
        shareCredentialsVersion = Some(1),
        endpoint = "foo",
        bearerToken = "bar",
        expirationTime = "2021-11-12T00:12:29.0Z"
      )
    )
  }

  test("oauth_client_credentials profile without optional scope") {
    testProfile(
      """{
        |  "shareCredentialsVersion": 2,
        |  "endpoint": "foo",
        |  "tokenEndpoint": "bar",
        |  "clientId": "abc",
        |  "clientSecret": "xyz",
        |  "type" : "oauth_client_credentials"
        |}
        |""".stripMargin,
      OAuthClientCredentialsDeltaSharingProfile(
        shareCredentialsVersion = Some(2),
        endpoint = "foo",
        tokenEndpoint = "bar",
        clientId = "abc",
        clientSecret = "xyz"
      )
    )
  }

  test("oauth_client_credentials profile with optional scope") {
    testProfile(
      """{
        |  "shareCredentialsVersion": 2,
        |  "endpoint": "foo",
        |  "tokenEndpoint": "bar",
        |  "clientId": "abc",
        |  "clientSecret": "xyz",
        |  "type" : "oauth_client_credentials",
        |  "scope": "testScope"
        |}
        |""".stripMargin,
      OAuthClientCredentialsDeltaSharingProfile(
        shareCredentialsVersion = Some(2),
        endpoint = "foo",
        tokenEndpoint = "bar",
        clientId = "abc",
        clientSecret = "xyz",
        scope = Some("testScope")
      )
    )
  }

  test("oauth_client_credentials only supports version 2") {
    val e = intercept[IllegalArgumentException] {
      testProfile(
        """{
          |  "shareCredentialsVersion": 1,
          |  "endpoint": "foo",
          |  "tokenEndpoint": "bar",
          |  "clientId": "abc",
          |  "clientSecret": "xyz",
          |  "type" : "oauth_client_credentials",
          |  "scope": "testScope"
          |}
          |""".stripMargin,
        null
      )
    }
    assert(e.getMessage.contains(s"oauth_client_credentials only supports version 2"))
  }

  test("oauth mandatory config is missing") {
    val mandatoryFields = Seq("endpoint", "tokenEndpoint", "clientId", "clientSecret")

    for (missingField <- mandatoryFields) {
      val profile = s"""{
                       |  "shareCredentialsVersion": 2,
                       |  "type" : "oauth_client_credentials",
                       |  ${mandatoryFields
                              .filter(_ != missingField)
                              .map(f => s""""$f": "value"""")
                              .mkString(",\n")}
                       |}""".stripMargin

      val e = intercept[IllegalArgumentException] {
        testProfile(profile, null)
      }
      assert(e.getMessage.contains(s"Cannot find the '$missingField' field in the profile file"))
    }
  }

  test("OAuthClientCredentialsDeltaSharingProfile.type is prepopulated") {
    val profile = OAuthClientCredentialsDeltaSharingProfile(
      shareCredentialsVersion = Some(2),
      endpoint = "foo",
      tokenEndpoint = "bar",
      clientId = "abc",
      clientSecret = "xyz",
      scope = Some("testScope")
    )

    assert(profile.profileType == "oauth_client_credentials")
  }

  test("DeltaSharingProfile.type is prepopulated") {
    val profile = DeltaSharingProfile(
      shareCredentialsVersion = Some(1),
      endpoint = "foo",
      bearerToken = "bar"
    )

    assert(profile.profileType == "bearer_token")
  }

  test("BearerTokenDeltaSharingProfile.type is prepopulated") {
    val profile = BearerTokenDeltaSharingProfile(
      shareCredentialsVersion = Some(1),
      endpoint = "foo",
      bearerToken = "bar"
    )

    assert(profile.profileType == "bearer_token")
  }
}
