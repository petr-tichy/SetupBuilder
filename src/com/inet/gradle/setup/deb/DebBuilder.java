/*
 * Copyright 2015 i-net software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.inet.gradle.setup.deb;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;

import org.gradle.api.internal.file.FileResolver;

import com.inet.gradle.setup.AbstractBuilder;
import com.inet.gradle.setup.DesktopStarter;
import com.inet.gradle.setup.Service;
import com.inet.gradle.setup.SetupBuilder;
import com.inet.gradle.setup.Template;
import com.inet.gradle.setup.deb.DebControlFileBuilder.Script;

public class DebBuilder extends AbstractBuilder<Deb> {

    private DebControlFileBuilder  controlBuilder;
    private DebDocumentFileBuilder documentBuilder;

    /**
     * Create a new instance
     * @param deb the calling task
     * @param setup the shared settings
     * @param fileResolver the file Resolver
     */
    public DebBuilder( Deb deb, SetupBuilder setup, FileResolver fileResolver ) {
        super( deb, setup, fileResolver );
    }

    /**
     * executes all necessary steps from copying to building the Debian package
     */
    public void build() {
        try {
            File filesPath = new File( buildDir, "/usr/share/" + setup.getBaseName() );
            task.copyTo( filesPath );
            changeFilePermissionsTo644( filesPath );

            // 	create the package config files in the DEBIAN subfolder

            controlBuilder = new DebControlFileBuilder( super.task, setup, new File( buildDir, "DEBIAN" ) );

            for( Service service : setup.getServices() ) {
                setupService( service );
            }
            
            for( DesktopStarter starter : setup.getDesktopStarters() ) {
                setupStarter( starter );
            }

            controlBuilder.build();

            documentBuilder = new DebDocumentFileBuilder( super.task, setup, new File( buildDir, "/usr/share/doc/" + setup.getBaseName() ) );
            documentBuilder.build();

            changeDirectoryPermissionsTo755( buildDir );

            createDebianPackage();

            checkDebianPackage();

        } catch( RuntimeException ex ) {
            throw ex;
        } catch( Exception ex ) {
            throw new RuntimeException( ex );
        }
    }


    /**
     * Creates the files and the corresponding script section for the specified service.
     * @param service the service
     * @throws IOException on errors during creating or writing a file
     */
    private void setupService( Service service ) throws IOException {
        String serviceUnixName = service.getName().toLowerCase().replace( ' ', '-' );
        Template initScript = new Template( "deb/template/init-service.sh" );
        initScript.setPlaceholder( "name", serviceUnixName );
        initScript.setPlaceholder( "displayName", setup.getApplication() );
        initScript.setPlaceholder( "description", service.getDescription() );
        initScript.setPlaceholder( "wait", "2" );
        initScript.setPlaceholder( "startArguments",
                                   "-cp /usr/share/" + setup.getBaseName() + "/" + service.getMainJar() + " " + service.getMainClass() + " " + service.getStartArguments() );
        String initScriptFile = "etc/init.d/" + serviceUnixName;
        initScript.writeTo( createFile( initScriptFile, true ) );
        controlBuilder.addConfFile( initScriptFile );
        controlBuilder.addTailScriptFragment( Script.POSTINST, "update-rc.d "+serviceUnixName+" defaults 91 09 >/dev/null" );
        controlBuilder.addTailScriptFragment( Script.POSTRM, "if [ \"$1\" = \"purge\" ] ; then\n" + 
            "    update-rc.d "+serviceUnixName+" remove >/dev/null\n" + 
            "fi" );
    }

    /**
     * Creates the files and the corresponding scripts for the specified desktop starter.
     * @param starter the desktop starter
     * @throws IOException on errors during creating or writing a file
     */
    private void setupStarter( DesktopStarter starter ) throws IOException {
        String unixName = starter.getName().toLowerCase().replace( ' ', '-' );
        String consoleStarterPath = "usr/bin/" + unixName;
        try (FileWriter fw = new FileWriter( createFile( consoleStarterPath, true ) )) {
            fw.write( "#!/bin/bash\n" );
            fw.write( "java -cp /usr/share/" + setup.getBaseName() + "/" + starter.getMainJar() + " " + starter.getMainClass() + " " + starter.getStartArguments()+" \"$@\"");
        }
        try (FileWriter fw = new FileWriter( createFile( "usr/share/applications/" + unixName + ".desktop", false ) )) {
            fw.write( "[Desktop Entry]\n" );
            fw.write( "Name="+starter.getName()+"\n" );
            fw.write( "Comment="+starter.getDescription().replace( '\n', ' ' )+"\n" );
//            fw.write( "GenericName=\r\n" );
            fw.write( "Exec=/"+consoleStarterPath+" %F\r\n" );
//            fw.write( "Icon=\r\n" );
            fw.write( "Terminal=false\r\n" );
            fw.write( "StartupNotify=true\r\n" );
            fw.write( "Type=Application\r\n" );
//            fw.write( "MimeType=\r\n" );
//            fw.write( "Categories=System;Utility;Core;GTK;FileTools;FileManager;\n" );
        }
        
    }

    /**
     * Creates a file in the build path structure.
     * @param path the path relative to the root of the build path
     * @param executable if set to <tt>true</tt> the executable bit will be set in the permission flags
     * @return the created file
     * @throws IOException on errors during creating the file or setting the permissions
     */
    private File createFile( String path, boolean executable ) throws IOException {
        File file = new File( buildDir, path );
        if( !file.getParentFile().exists() ) {
            file.getParentFile().mkdirs();
        }
        file.createNewFile();

        DebUtils.setPermissions( file, executable );
        return file;
    }

    /**
     * execute the lintian tool to check the Debian package This will only be executed if the task 'checkPackage'
     * property is set to true
     */
    private void checkDebianPackage() {
        if( task.getCheckPackage() == null || task.getCheckPackage().equalsIgnoreCase( "true" ) ) {
            ArrayList<String> command = new ArrayList<>();
            command.add( "lintian" );
            //    		command.add( "-d" );
            command.add( setup.getDestinationDir().getAbsolutePath() + "/" + setup.getSetupName() + "." + task.getExtension() );
            exec( command );
        }
    }

    /**
     * execute the command to generate the Debian package
     */
    private void createDebianPackage() {
        ArrayList<String> command = new ArrayList<>();
        command.add( "fakeroot" );
        command.add( "dpkg-deb" );
        command.add( "--build" );
        command.add( buildDir.getAbsolutePath() );
        command.add( setup.getDestinationDir().getAbsolutePath() + "/" + setup.getSetupName() + "." + task.getExtension() );
        exec( command );
    }

    /**
     * Changes the permissions of all directories recursively inside the specified path to 755.
     * @param path the path
     * @throws IOException on I/O failures
     */
    private void changeDirectoryPermissionsTo755( File path ) throws IOException {
     	DebUtils.setPermissions( path, true );
        for( File file : path.listFiles() ) {
            if( file.isDirectory() ) {
                changeDirectoryPermissionsTo755( file );
            }
        }
    }

    /**
     * Changes the permissions of all files recursively inside the specified path to 644.
     * @param path the path
     * @throws IOException on I/O failures
     */
    private void changeFilePermissionsTo644( File path ) throws IOException {
        for( File file : path.listFiles() ) {
            if( file.isDirectory() ) {
                changeFilePermissionsTo644( file );
            } else {
                DebUtils.setPermissions( file, false );
            }
        }
    }
}
