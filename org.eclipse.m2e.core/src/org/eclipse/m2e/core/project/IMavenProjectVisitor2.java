/*******************************************************************************
 * Copyright (c) 2008-2010 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *      Sonatype, Inc. - initial API and implementation
 *******************************************************************************/

package org.eclipse.m2e.core.project;

import org.apache.maven.artifact.Artifact;


/**
 * IMavenProjectVisitor2
 * 
 * @author Igor Fedorenko
 * @deprecated will be removed before 1.0
 */
public interface IMavenProjectVisitor2 extends IMavenProjectVisitor {

  /**
   * @param mavenProjectFacade
   * @param artifact
   */
  void visit(IMavenProjectFacade mavenProjectFacade, Artifact artifact);

}
