// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.gradle.tooling.tasks;

public class BaseComponentNode extends AbstractComponentNode {
  private final String name;

  public BaseComponentNode(long id, String name) {
    super(id);
    this.name = name;
  }

  @Override
  public String getDisplayName() {
    return name;
  }
}
