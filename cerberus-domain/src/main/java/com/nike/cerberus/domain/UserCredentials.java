/*
 * Copyright (c) 2020 Nike, inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nike.cerberus.domain;

import java.util.Arrays;
import lombok.Getter;
import lombok.Setter;

/** Represents the user's credentials sent during authentication. */
public class UserCredentials {

  @Getter @Setter private String username;

  private byte[] password;

  public UserCredentials(String username, byte[] password) {
    this.username = username;
    this.password = Arrays.copyOf(password, password.length);
  }

  public byte[] getPassword() {
    return Arrays.copyOf(password, password.length);
  }

  public void setPassword(byte[] password) {
    this.password = password != null ? Arrays.copyOf(password, password.length) : null;
  }
}
