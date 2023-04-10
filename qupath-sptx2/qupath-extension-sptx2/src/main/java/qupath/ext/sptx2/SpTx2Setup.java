package qupath.ext.sptx2;

public class SpTx2Setup {
    private String stardistModelLocationPath;
    private String pathDetObjImgAcqDistDir;
    private String pathDetObjImgAcqPrefix;
    private double pathDetObjImgAcqMPP;
    private boolean pathDetObjImgAcqDontRescaling;
    private int pathDetObjImgAcqSamplingSize;
    private int pathDetObjImgAcqSamplingNum;
    private String pathDetObjImgAcqSamplingFmt;
    
    private static SpTx2Setup instance = new SpTx2Setup();

    public static SpTx2Setup getInstance() {
        return instance;
    }
    
    public String getStardistModelLocationPath() {
        return stardistModelLocationPath;
    }

    public void setStardistModelLocationPath(String stardistModelLocationPath) {
        this.stardistModelLocationPath = stardistModelLocationPath;
    }

//    public String getPathDetObjImgAcqDistDir() {
//        return pathDetObjImgAcqDistDir;
//    }
//
//    public void setPathDetObjImgAcqDistDir(String pathDetObjImgAcqDistDir) {
//        this.pathDetObjImgAcqDistDir = pathDetObjImgAcqDistDir;
//    }
//    
//    public String getPathDetObjImgAcqPrefix() {
//        return pathDetObjImgAcqPrefix;
//    }
//
//    public void setPathDetObjImgAcqPrefix(String pathDetObjImgAcqPrefix) {
//        this.pathDetObjImgAcqPrefix = pathDetObjImgAcqPrefix;
//    }
//    
//    public double getPathDetObjImgAcqMPP() {
//        return pathDetObjImgAcqMPP;
//    }
//
//    public void setPathDetObjImgAcqMPP(Number pathDetObjImgAcqMPP) {
//        this.pathDetObjImgAcqMPP = pathDetObjImgAcqMPP.doubleValue();
//    }
//    
//    
//    public boolean getPathDetObjImgAcqDontRescaling() {
//        return pathDetObjImgAcqDontRescaling;
//    }
//
//    public void setPathDetObjImgAcqDontRescaling(boolean pathDetObjImgAcqDontRescaling) {
//        this.pathDetObjImgAcqDontRescaling = pathDetObjImgAcqDontRescaling;
//    }
//    
//    
//    
//    
//    
//    public int getPathDetObjImgAcqSamplingSize() {
//        return pathDetObjImgAcqSamplingSize;
//    }
//
//    public void setPathDetObjImgAcqSamplingSize(int pathDetObjImgAcqSamplingSize) {
//        this.pathDetObjImgAcqSamplingSize = pathDetObjImgAcqSamplingSize;
//    }
//    
//    
//    
//    
//    
//    public int getPathDetObjImgAcqSamplingNum() {
//        return pathDetObjImgAcqSamplingNum;
//    }
//
//    public void setPathDetObjImgAcqSamplingNum(int pathDetObjImgAcqSamplingNum) {
//        this.pathDetObjImgAcqSamplingNum = pathDetObjImgAcqSamplingNum;
//    }
//    
//    
//    
//    public String getPathDetObjImgAcqSamplingFmt() {
//        return pathDetObjImgAcqSamplingFmt;
//    }
//
//    public void setPathDetObjImgAcqSamplingFmt(String pathDetObjImgAcqSamplingFmt) {
//        this.pathDetObjImgAcqSamplingFmt = pathDetObjImgAcqSamplingFmt;
//    }
}