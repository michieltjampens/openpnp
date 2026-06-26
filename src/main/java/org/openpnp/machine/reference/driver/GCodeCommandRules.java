package org.openpnp.machine.reference.driver;

import java.util.regex.Pattern;

public class GCodeCommandRules {
    private boolean removeComments=false;
    private boolean compressGcode=false;
    private String compressionExcludes = "[]\"";
    private boolean isLogging=false;
    protected boolean backslashEscapedCharactersEnabled = false;
    Pattern confirmCommandRegex;
    Pattern errorCommandRegex;
    long dollarWaitTimeMilliseconds=50;

    public GCodeCommandRules() {
        confirmCommandRegex = Pattern.compile("^ok.*");
        errorCommandRegex = Pattern.compile("^error.*");
    }
    public boolean isConfirmationMessage(String message) {
        return confirmCommandRegex.asMatchPredicate().test(message);
    }
    public boolean isErrorMessage(String message) {
        return errorCommandRegex.asMatchPredicate().test(message);
    }
    public void setConfirmCommandRegex( String regex) {
        confirmCommandRegex = Pattern.compile(regex);
    }
    public void setErrorCommandRegex( String regex) {
        errorCommandRegex = Pattern.compile(regex);
    }
    public long getDollarWaitTimeMilliseconds() {
        return dollarWaitTimeMilliseconds;
    }
    public boolean isLogging() {
        return isLogging;
    }
    public void enableLogging() {
        this.isLogging=true;
    }
    public void disableLogging() {
        this.isLogging=false;
    }
    public void setLogging( boolean logging ){
        this.isLogging=logging;
    }
    public boolean needsCleaning(){
        return removeComments||compressGcode;
    }
    public boolean doCompress(){
        return compressGcode;
    }
    public void setCleaning( boolean compress, boolean removeComments){
        this.compressGcode=compress;
        this.removeComments=removeComments;
    }
    public boolean removeComments(){
        return removeComments;
    }
    public String getCompressionExcludes() {
        return compressionExcludes;
    }

    public void setCompressionExcludes(String compressionExcludes) {
        this.compressionExcludes = compressionExcludes;
    }
    public boolean allowCompression( char ch ){
        return compressionExcludes.contains(String.valueOf(ch));
    }
    public char[] getCompressionExcludesCharArray(){
        return compressionExcludes.toCharArray();
    }
    public boolean isBackslashEscapedCharactersEnabled(){
        return backslashEscapedCharactersEnabled;
    }
}
