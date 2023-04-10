/*-
 * #%L
 * ST-AnD is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * ST-AnD is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License 
 * along with ST-AnD.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.ext.sptx2;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.opencsv.CSVReader;

import ij.gui.Roi;
import ij.plugin.filter.ThresholdToSelection;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import javafx.beans.property.StringProperty;
import javafx.geometry.Point2D;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import qupath.imagej.tools.IJTools;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.PathRootObject;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractDetectionPlugin;
import qupath.lib.plugins.DetectionPluginTools;
import qupath.lib.plugins.ObjectDetector;
import qupath.lib.plugins.PluginRunner;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.RoiTools;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.interfaces.ROI;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.json.JSONArray;
import org.json.JSONObject;
/**
 * Plugin for loading 10x Visium Annotation 
 * 
 * @author Chao Hui Huang
 *
 */
public class CosmxAnnotation extends AbstractDetectionPlugin<BufferedImage> {
	
//	final private static Logger logger = LoggerFactory.getLogger(CosmxAnnotation.class);
	
	final private StringProperty cosmxAntnCosmxFldrProp = PathPrefs.createPersistentPreference("cosmxAntnCosmxFldr", ""); 
	
	private ParameterList params;

	private String lastResults = null;
	
	/**
	 * Constructor.
	 */
	public CosmxAnnotation() {
		params = new ParameterList()
			.addTitleParameter("NanoString Cosmx Data Loader")
			.addStringParameter("cosmxDir", "Cosmx directory", cosmxAntnCosmxFldrProp.get(), "Cosmx Out Directory")
//			.addBooleanParameter("fromTranscriptFile", "Load directly from transcript raw data file? (default: false)", false, "Load data from transcript file directly? (default: false)")
			.addBooleanParameter("consolToAnnot", "Consolidate transcript data to Visium-style spots? (default: false)", false, "Consolidate Transcript Data to Annotations? (default: false)")
			.addEmptyParameter("")
			.addBooleanParameter("inclGeneExpr", "Include Gene Expression? (default: true)", true, "Include Gene Expression? (default: true)")		
//			.addBooleanParameter("inclBlankCodeword", "Include Blank Codeword? (default: false)", false, "Include Blank Codeword? (default: false)")		
//			.addBooleanParameter("inclNegCtrlCodeword", "Include Negative Control Codeword? (default: false)", false, "Include Negative Control Codeword? (default: false)")		
			.addBooleanParameter("inclNegCtrlProbe", "Include Negative Control Probe? (default: false)", false, "Include Negative Control Probe? (default: false)")		
			.addEmptyParameter("")
//			.addEmptyParameter("Options for loading raw transcript data")
//			.addDoubleParameter("qv", "Minimal Q-Value", 0.0, null, "Minimal Q-Value")		
//			.addBooleanParameter("transcriptOnNucleusOnly", "Only the transcripts overlapped on nucleus? (default: true)", true, "Only the transcripts overlapped on nucleus? (default: true)")		
//			.addBooleanParameter("transcriptBelongsToCell", "Only the transcripts belongs to a cell (based on DAPI)? (default: true)", true, "Only the transcripts belongs to a cell? (default: true)")		
//			.addEmptyParameter("")
			.addIntParameter("maskDownsampling", "Downsampling for transcript to cell assignment", 2, null, "Downsampling for cell-transciptome assignment")			
			;
	}
	
	class AnnotationLoader implements ObjectDetector<BufferedImage> {
		
		@Override
		public Collection<PathObject> runDetection(final ImageData<BufferedImage> imageData, final ParameterList params, final ROI pathROI) throws IOException {
			cosmxAntnCosmxFldrProp.set(params.getStringParameterValue("cosmxDir"));
			
			final ImageServer<BufferedImage> server = imageData.getServer();				
			final PathObjectHierarchy hierarchy = imageData.getHierarchy();
			final ArrayList<PathObject> resultPathObjectList = new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
			
			try {
				
//				final InputStream is = new FileInputStream(Paths.get(params.getStringParameterValue("cosmxDir"), "affine_matrix.json").toString());
//				final String jsonTxt = IOUtils.toString(is, "UTF-8");
//				final JSONObject jsonObj = new JSONObject(jsonTxt);    
//				
//				final double dapiImageHeightMicrons = jsonObj.getDouble("dapi_height");
//				final double dapiImagePixelSizeMicrons = jsonObj.getDouble("dapi_pixel_size");
//				final double[] affineMtx = IntStream.range(0, 6).mapToDouble(i -> jsonObj.getJSONArray("affine_matrix").getDouble(i)).toArray();
//					            
		        final double pixelSizeMicrons = server.getPixelCalibration().getAveragedPixelSizeMicrons();
		        
	            /*
	             * Generate cell masks with their labels
	             */
				
				final List<PathObject> selectedAnnotationPathObjectList = new ArrayList<>();
				
				for (PathObject pathObject : hierarchy.getSelectionModel().getSelectedObjects()) {
					if (pathObject.isAnnotation() && pathObject.hasChildObjects())
						selectedAnnotationPathObjectList.add(pathObject);
				}	
				
				if(selectedAnnotationPathObjectList.isEmpty()) throw new Exception("Missed selected annotations");

				final int maskDownsampling = params.getIntParameterValue("maskDownsampling");;
				final int maskWidth = (int)Math.round(imageData.getServer().getWidth()/maskDownsampling);
				final int maskHeight = (int)Math.round(imageData.getServer().getHeight()/maskDownsampling);	
				
				
				
				
				final BufferedImage annotPathObjectImageMask = new BufferedImage(maskWidth, maskHeight, BufferedImage.TYPE_INT_RGB);
				final List<PathObject> annotPathObjectList = new ArrayList<PathObject>();						
				
				final Graphics2D annotPathObjectG2D = annotPathObjectImageMask.createGraphics();				
				annotPathObjectG2D.setBackground(new Color(0, 0, 0));
				annotPathObjectG2D.clearRect(0, 0, maskWidth, maskHeight);
				
				annotPathObjectG2D.setClip(0, 0, maskWidth, maskHeight);
				annotPathObjectG2D.scale(1.0/maskDownsampling, 1.0/maskDownsampling);					    
				
				
				
				
				
				final BufferedImage pathObjectImageMask = new BufferedImage(maskWidth, maskHeight, BufferedImage.TYPE_INT_RGB);
				final List<PathObject> pathObjectList = new ArrayList<PathObject>();						
				
				final Graphics2D pathObjectG2D = pathObjectImageMask.createGraphics();				
				pathObjectG2D.setBackground(new Color(0, 0, 0));
				pathObjectG2D.clearRect(0, 0, maskWidth, maskHeight);
				
				pathObjectG2D.setClip(0, 0, maskWidth, maskHeight);
				pathObjectG2D.scale(1.0/maskDownsampling, 1.0/maskDownsampling);
				
				try {
					int annotPathObjectCount = 1;
					int pathObjectCount = 1;
					
					for(PathObject p: selectedAnnotationPathObjectList) {
						
						
						annotPathObjectList.add(p);
					    
					    final int pb0 = (annotPathObjectCount & 0xff) >> 0; // b
					    final int pb1 = (annotPathObjectCount & 0xff00) >> 8; // g
					    final int pb2 = (annotPathObjectCount & 0xff0000) >> 16; // r
					    final Color pMaskColor = new Color(pb2, pb1, pb0); // r, g, b
				    
					    final ROI pRoi = p.getROI();
						final Shape pShape = pRoi.getShape();
						
						annotPathObjectG2D.setColor(pMaskColor);
						annotPathObjectG2D.fill(pShape);
						
						annotPathObjectCount ++;
					    if(annotPathObjectCount == 0xffffff) {
					    	throw new Exception("annotation count overflow!");
					    }
						
						for(PathObject c: p.getChildObjects()) {
							pathObjectList.add(c);
						    
						    final int b0 = (pathObjectCount & 0xff) >> 0; // b
						    final int b1 = (pathObjectCount & 0xff00) >> 8; // g
						    final int b2 = (pathObjectCount & 0xff0000) >> 16; // r
						    final Color maskColor = new Color(b2, b1, b0); // r, g, b
					    
						    final ROI roi = c.getROI();
							final Shape shape = roi.getShape();
							
							pathObjectG2D.setColor(maskColor);
							pathObjectG2D.fill(shape);
							
							pathObjectCount ++;
						    if(pathObjectCount == 0xffffff) {
						    	throw new Exception("Cell count overflow!");
						    }
						}
					}	
				}
				catch(Exception e) {
					throw e;
				}
				finally {
					annotPathObjectG2D.dispose();	
					pathObjectG2D.dispose();	
				}
				
	            /*
	             * Read single cell data
	             * "cell_id","x_centroid","y_centroid","transcript_counts","control_probe_counts","control_codeword_counts","total_counts","cell_area","nucleus_area"
	             */
				
				
				if(params.getStringParameterValue("cosmxDir").isBlank()) throw new Exception("singleCellFile is blank");
				
//				final HashMap<Integer, Integer> cellToClusterHashMap = new HashMap<>();
//				
//				final String clusterFilePath = java.nio.file.Paths.get(params.getStringParameterValue("cosmxDir"), "analysis", "clustering", "gene_expression_graphclust", "clusters.csv").toString();
//				final FileReader clusterFileReader = new FileReader(new File(clusterFilePath));
//				final BufferedReader clusterReader = new BufferedReader(clusterFileReader);
//				clusterReader.readLine();
//				String clusterNextRecord;
//				
//				while ((clusterNextRecord = clusterReader.readLine()) != null) {
//		        	final String[] clusterNextRecordArray = clusterNextRecord.split(",");
//		        	final int cellId = Integer.parseInt(clusterNextRecordArray[0]);
//		        	final int clusterId = Integer.parseInt(clusterNextRecordArray[1]);
//		        	cellToClusterHashMap.put(cellId, clusterId);
//				}
//				
//				clusterReader.close();
				
				final HashMap<String, PathObject> cellToPathObjHashMap = new HashMap<>();
			
//				final String singleCellFilePath = java.nio.file.Paths.get(params.getStringParameterValue("cosmxDir"), "cells.csv.gz").toString();
//				final GZIPInputStream singleCellGzipStream = new GZIPInputStream(new FileInputStream(singleCellFilePath));
//				final BufferedReader singleCellGzipReader = new BufferedReader(new InputStreamReader(singleCellGzipStream));
//				singleCellGzipReader.readLine();
//				String singleCellNextRecord;
				
				final File cosmxDir = new File(params.getStringParameterValue("cosmxDir"));
				
				
				
				
				
				
				
				final FileFilter cosmxFovPosFileFilter = new WildcardFileFilter("*fov_positions_file.csv");
				final File[] cosmxFovPosFileList = cosmxDir.listFiles(cosmxFovPosFileFilter);
				if(cosmxFovPosFileList.length != 1) throw new Exception("*fov_positions_file.csv error");
				
				final FileReader fovPosFileReader = new FileReader(new File(cosmxFovPosFileList[0].toString()));
				final BufferedReader fovPosBufferedReader = new BufferedReader(fovPosFileReader);
				fovPosBufferedReader.readLine();
				String fovPosNextRecord;
				
				int x_global_min = -1;
				int y_global_min = -1;
				
		        while ((fovPosNextRecord = fovPosBufferedReader.readLine()) != null) {
		        	final String[] fovPosNextRecordArray = fovPosNextRecord.split(",");
		        	// "fov","x_global_px","y_global_px"
		        	
		        	final int x_global_px = (int)(0.5+Double.parseDouble(fovPosNextRecordArray[1]));
		        	final int y_global_px = (int)(0.5+Double.parseDouble(fovPosNextRecordArray[2]));
		        	
		        	if(x_global_min == -1 || x_global_px < x_global_min) x_global_min = x_global_px;
		        	if(y_global_min == -1 || y_global_px < y_global_min) y_global_min = y_global_px;
		        }
				
		        fovPosBufferedReader.close();
				
				
				
				
				
				final FileFilter cosmxMetadataFileFilter = new WildcardFileFilter("*metadata_file.csv");
				final File[] cosmxMetadataFileList = cosmxDir.listFiles(cosmxMetadataFileFilter);
				if(cosmxMetadataFileList.length != 1) throw new Exception("*metadata_file.csv error");
				
				final FileReader singleCellFileReader = new FileReader(new File(cosmxMetadataFileList[0].toString()));
				final BufferedReader singleCellBufferedReader = new BufferedReader(singleCellFileReader);
				singleCellBufferedReader.readLine();
				String singleCellNextRecord;
				
		        while ((singleCellNextRecord = singleCellBufferedReader.readLine()) != null) {
				        
		        	final String[] singleCellNextRecordArray = singleCellNextRecord.split(",");
		        	
		        	// "fov","cell_ID","Area","AspectRatio","CenterX_local_px","CenterY_local_px","CenterX_global_px","CenterY_global_px","Width","Height","Mean.MembraneStain","Max.MembraneStain",

		        	
		        	final int fov = Integer.parseInt(singleCellNextRecordArray[0].replaceAll("\"", ""));
		        	final int cellId = Integer.parseInt(singleCellNextRecordArray[1].replaceAll("\"", ""));
		        	
//		        	final double transcriptCounts = Double.parseDouble(singleCellNextRecordArray[3]);
//		        	final double controlProbeCounts = Double.parseDouble(singleCellNextRecordArray[4]);
//		        	final double controlCodewordCounts = Double.parseDouble(singleCellNextRecordArray[5]);
//		        	final double totalCounts = Double.parseDouble(singleCellNextRecordArray[6]);
//		        	final double cellArea = Double.parseDouble(singleCellNextRecordArray[7]);
//		        	final double nucleusArea = Double.parseDouble(singleCellNextRecordArray[8]);
		        	
		        	final double cx = Double.parseDouble(singleCellNextRecordArray[6]);
		        	final double cy = Double.parseDouble(singleCellNextRecordArray[7]);
		        	
		        	final double dx = cx-x_global_min;
		        	final double dy = cy-y_global_min;
		        	
//		        	final double aX = affineMtx[0] * dx + affineMtx[1] * dy + affineMtx[2] * 1.0;
//		        	final double aY = affineMtx[3] * dx + affineMtx[4] * dy + affineMtx[5] * 1.0;
		     
//		        	final int fX = (int)Math.round(aX / maskDownsampling);
//		        	final int fY = (int)Math.round(aY / maskDownsampling);
		        	
		        	final int fX = (int)(0.5+dx/maskDownsampling);
		        	final int fY = (int)(0.5+dy/maskDownsampling);
		        	
		        	if(fX < 0 || fX >= pathObjectImageMask.getWidth() || fY < 0 || fY >= pathObjectImageMask.getHeight()) continue;
		        	
		        	final int v = pathObjectImageMask.getRGB(fX, fY);
		        	final int d0 = v&0xff;
		        	final int d1 = (v>>8)&0xff;
		        	final int d2 = (v>>16)&0xff;
					final int r = d2*0x10000+d1*0x100+d0;
				    
		        	if(r == 0) continue; // This location doesn't have a cell.
			        	
		        	final int pathObjectId = r - 1;  // pathObjectId starts at 1, since 0 means background
			        	
		        	final PathObject cellPathObject = pathObjectList.get(pathObjectId);
		        	cellToPathObjHashMap.put(String.valueOf(cellId)+"_"+String.valueOf(fov), cellPathObject);
		        	
//		        	final Integer clusterId = cellToClusterHashMap.get(cellId);
		        	
//		        	if(clusterId != null) {
//		        		final PathClass pathCls = PathClassFactory.getPathClass("cosmx:cluster:"+Integer.toString(clusterId));
//			        	cellPathObject.setPathClass(pathCls);
//		        	}
		        	
		        	final double roiX = cellPathObject.getROI().getCentroidX();
		        	final double roiY = cellPathObject.getROI().getCentroidY();
		        	final double newDist = (new Point2D(dx, dy).distance(roiX, roiY))*pixelSizeMicrons;
		        	final MeasurementList pathObjMeasList = cellPathObject.getMeasurementList();
		        	if(pathObjMeasList.containsKey("cosmx:cell:cell_id")) {
		        		final double minDist = pathObjMeasList.get("cosmx:cell:displacement");
		        		if(newDist < minDist) {
		        			pathObjMeasList.put("cosmx:cell:fov", fov);
		        			pathObjMeasList.put("cosmx:cell:cell_id", cellId);
		        			pathObjMeasList.put("cosmx:cell:displacement", newDist);
		        			pathObjMeasList.put("cosmx:cell:x_centroid", (double)dx);
		        			pathObjMeasList.put("cosmx:cell:y_centroid", (double)dy);
//		        			if(clusterId != null) pathObjMeasList.putMeasurement("cosmx:cell:cluster_id", clusterId);
//		        			pathObjMeasList.put("cosmx:cell:transcript_counts", transcriptCounts);
//		        			pathObjMeasList.put("cosmx:cell:control_probe_counts", controlProbeCounts);
//		        			pathObjMeasList.put("cosmx:cell:control_codeword_counts", controlCodewordCounts);
//		        			pathObjMeasList.put("cosmx:cell:total_counts", totalCounts);
//		        			pathObjMeasList.put("cosmx:cell:cell_area", cellArea);
//		        			pathObjMeasList.put("cosmx:cell:nucleus_area", nucleusArea);
		        		}
		        	}
		        	else {
		        		pathObjMeasList.put("cosmx:cell:fov", fov);
		        		pathObjMeasList.put("cosmx:cell:cell_id", cellId);
	        			pathObjMeasList.put("cosmx:cell:displacement", newDist);
	        			pathObjMeasList.put("cosmx:cell:x_centroid", (double)dx);
	        			pathObjMeasList.put("cosmx:cell:y_centroid", (double)dy);
//	        			if(clusterId != null) pathObjMeasList.put("cosmx:cell:cluster_id", clusterId);
//	        			pathObjMeasList.put("cosmx:cell:transcript_counts", transcriptCounts);
//	        			pathObjMeasList.put("cosmx:cell:control_probe_counts", controlProbeCounts);
//	        			pathObjMeasList.put("cosmx:cell:control_codeword_counts", controlCodewordCounts);
//	        			pathObjMeasList.put("cosmx:cell:total_counts", totalCounts);
//	        			pathObjMeasList.put("cosmx:cell:cell_area", cellArea);
//	        			pathObjMeasList.put("cosmx:cell:nucleus_area", nucleusArea);     		        
		        	}
		        	
		        	pathObjMeasList.close(); 
		        	
		        	
		        	
 
	        	}		        	
	        	
	
	        	
	        	
	        	
	        	
	        	
		        	
		        singleCellBufferedReader.close();
				
				
				/*
	             * Read feature matrix data
	             */
					
//		        if(!params.getBooleanParameterValue("fromTranscriptFile")) {
//					final String barcodeFilePath = java.nio.file.Paths.get(params.getStringParameterValue("cosmxDir"), "cell_feature_matrix", "barcodes.tsv.gz").toString();
//					final String featureFilePath = java.nio.file.Paths.get(params.getStringParameterValue("cosmxDir"), "cell_feature_matrix", "features.tsv.gz").toString();
//					final String matrixFilePath = java.nio.file.Paths.get(params.getStringParameterValue("cosmxDir"), "cell_feature_matrix", "matrix.mtx.gz").toString();
					
//					final GZIPInputStream barcodeGzipStream = new GZIPInputStream(new FileInputStream(barcodeFilePath));
//					final BufferedReader barcodeGzipReader = new BufferedReader(new InputStreamReader(barcodeGzipStream));
					
//			        final List<Integer> barcodeList = new ArrayList<>();
//			        
//			        String barcodeNextRecord;
//			        while ((barcodeNextRecord = barcodeGzipReader.readLine()) != null) {
//			        	barcodeList.add(Integer.parseInt(barcodeNextRecord));
//			        }
//			        
//			        final List<String> featureIdList = new ArrayList<>();
//			        final List<String> featureNameList = new ArrayList<>();
//			        final List<String> featureTypeList = new ArrayList<>();
//			        
//					final GZIPInputStream featureGzipStream = new GZIPInputStream(new FileInputStream(featureFilePath));
//					final BufferedReader featureGzipReader = new BufferedReader(new InputStreamReader(featureGzipStream));
//			        
//			        String featureNextRecord;
//			        while ((featureNextRecord = featureGzipReader.readLine()) != null) {
//			        	final String[] featureNextRecordArray = featureNextRecord.split("\t");
//			        	featureIdList.add(featureNextRecordArray[0]);
//			        	featureNameList.add(featureNextRecordArray[1]);
//			        	featureTypeList.add(featureNextRecordArray[2]);
//			        }
//			        
//			        final GZIPInputStream matrixGzipStream = new GZIPInputStream(new FileInputStream(matrixFilePath));
//			        final BufferedReader matrixGzipReader = new BufferedReader(new InputStreamReader(matrixGzipStream), '\t');
//					
//			        matrixGzipReader.readLine();
//			        matrixGzipReader.readLine();
//			        matrixGzipReader.readLine();
			        
//			        final int[][] matrix = new int[featureNameList.size()][barcodeList.size()];
//			        
//			        String matrixNextRecord;
//			        while ((matrixNextRecord = matrixGzipReader.readLine()) != null) {
//			        	final String[] matrixNextRecordArray = matrixNextRecord.split(" ");
//			        	final int f = Integer.parseInt(matrixNextRecordArray[0])-1;
//			        	final int b = Integer.parseInt(matrixNextRecordArray[1])-1;
//			        	final int v = Integer.parseInt(matrixNextRecordArray[2]);
//			        	
//			        	matrix[f][b] = v;
//			        }
			        
			        
//			        for(int b = 0; b < barcodeList.size(); b ++) {
//			        	if(cellToPathObjHashMap.containsKey(barcodeList.get(b))) {
//				        	final PathObject c = cellToPathObjHashMap.get(barcodeList.get(b));
//				        	final MeasurementList pathObjMeasList = c.getMeasurementList();
//				        	
//				        	for(int f = 0; f < featureNameList.size(); f ++) {	
//				        		if(!params.getBooleanParameterValue("inclBlankCodeword") && (featureTypeList.get(f).compareTo("Blank Codeword") == 0)) continue;
//			        			if(!params.getBooleanParameterValue("inclGeneExpr") && (featureTypeList.get(f).compareTo("Gene Expression") == 0)) continue;
//			        			if(!params.getBooleanParameterValue("inclNegCtrlCodeword") && (featureTypeList.get(f).compareTo("Negative Control Codeword") == 0)) continue;
//			        			if(!params.getBooleanParameterValue("inclNegCtrlProbe") && (featureTypeList.get(f).compareTo("Negative Control Probe") == 0)) continue;
//				        		
//			        			pathObjMeasList.put("cosmx:cell_transcript:"+featureNameList.get(f), matrix[f][b]);  
//				        			 
//				        	}
//				        	
//				        	pathObjMeasList.close();
//				        	
//				        	if(params.getBooleanParameterValue("consolToAnnot") && hierarchy.getRootObject() != c.getParent()) {
//				        		
//				        		final MeasurementList parentPathObjMeasList = c.getParent().getMeasurementList();
//				        		
//				        		for(int f = 0; f < featureNameList.size(); f ++) {	
//				        			if(!params.getBooleanParameterValue("inclBlankCodeword") && (featureTypeList.get(f).compareTo("Blank Codeword") == 0)) continue;
//				        			if(!params.getBooleanParameterValue("inclGeneExpr") && (featureTypeList.get(f).compareTo("Gene Expression") == 0)) continue;
//				        			if(!params.getBooleanParameterValue("inclNegCtrlCodeword") && (featureTypeList.get(f).compareTo("Negative Control Codeword") == 0)) continue;
//				        			if(!params.getBooleanParameterValue("inclNegCtrlProbe") && (featureTypeList.get(f).compareTo("Negative Control Probe") == 0)) continue;
//				        			
//				        			final double oldVal = 
//				        					parentPathObjMeasList.containsKey("cosmx:spot_transcript:"+featureNameList.get(f))? 
//				        					parentPathObjMeasList.get("cosmx:spot_transcript:"+featureNameList.get(f)): 
//				        					0.0;
//				        	
//					        		parentPathObjMeasList.put("cosmx:spot_transcript:"+featureNameList.get(f), matrix[f][b]+oldVal);  
//					        	}
//				        		
//					        	parentPathObjMeasList.close();
//				        	}
//			        	}
//			        }
			        
			        
			        
		        final FileFilter cosmxExprMatFileFilter = new WildcardFileFilter("*exprMat_file.csv");
				final File[] cosmxExprMatFileList = cosmxDir.listFiles(cosmxExprMatFileFilter);
				if(cosmxExprMatFileList.length != 1) throw new Exception("*exprMat_file.csv");
				
				final FileReader exprMatFileReader = new FileReader(new File(cosmxExprMatFileList[0].toString()));
				final BufferedReader exprMatBufferedReader = new BufferedReader(exprMatFileReader);
				final String[] exprMatHeaders = exprMatBufferedReader.readLine().split(",");
				
				String exprMatNextRecord;
		        while ((exprMatNextRecord = exprMatBufferedReader.readLine()) != null) {
		        	final String[] exprMatNextRecordArray = exprMatNextRecord.split(",");
		        	
		        	final int fov = Integer.parseInt(exprMatNextRecordArray[0].replaceAll("\"", ""));
		        	final int cellId = Integer.parseInt(exprMatNextRecordArray[1].replaceAll("\"", ""));
		        	
		        	if(cellToPathObjHashMap.containsKey(String.valueOf(cellId)+"_"+String.valueOf(fov))) {
		        		final PathObject c = cellToPathObjHashMap.get(String.valueOf(cellId)+"_"+String.valueOf(fov));
			        	final MeasurementList pathObjMeasList = c.getMeasurementList();
		        		
		        		for(int i = 2; i < exprMatNextRecordArray.length; i ++) {
			        		if(!params.getBooleanParameterValue("inclNegCtrlProbe") && (exprMatHeaders[i].replaceAll("\"", "").startsWith("NegPrb"))) continue;
			        		
			        		pathObjMeasList.put("cosmx:cell_transcript:"+exprMatHeaders[i].replaceAll("\"", ""), Double.parseDouble(exprMatNextRecordArray[i]));  
			        	}
		        		
		        		pathObjMeasList.close();
		        		
		        		
		        		
		        		
		        		if(params.getBooleanParameterValue("consolToAnnot") && hierarchy.getRootObject() != c.getParent()) {
			        		
			        		final MeasurementList parentPathObjMeasList = c.getParent().getMeasurementList();
			        		
			        		for(int f = 0; f < (exprMatNextRecordArray.length-2); f ++) {	
			        			if(!params.getBooleanParameterValue("inclNegCtrlProbe") && (exprMatNextRecordArray[f].replaceAll("\"", "").startsWith("NegPrb"))) continue;
			        			
			        			final double oldVal = 
			        					parentPathObjMeasList.containsKey("cosmx:spot_transcript:"+exprMatNextRecordArray[f])? 
			        					parentPathObjMeasList.get("cosmx:spot_transcript:"+exprMatNextRecordArray[f]): 
			        					0.0;
			        	
				        		parentPathObjMeasList.put("cosmx:spot_transcript:"+exprMatNextRecordArray[f], Double.parseDouble(exprMatNextRecordArray[f])+oldVal);  
				        	}
			        		
				        	parentPathObjMeasList.close();
			        	}
			        }
		        }
		        
		        
		        
		        
		        
		        
		        
//		        final FileFilter cosmxClusterFileFilter = new WildcardFileFilter("cluster.csv");
//				final File[] cosmxClusterFileList = cosmxDir.listFiles(cosmxClusterFileFilter);
//				if(cosmxClusterFileList.length != 1) throw new Exception("*cluster.csv");
//				
//				final FileReader clusterFileReader = new FileReader(new File(cosmxClusterFileList[0].toString()));
//				final BufferedReader clusterBufferedReader = new BufferedReader(clusterFileReader);
//				final String[] clusterHeaders = clusterBufferedReader.readLine().split(",");
//				
//				String clusterNextRecord;
//		        while ((clusterNextRecord = clusterBufferedReader.readLine()) != null) {
//		        	final String[] clusterNextRecordArray = clusterNextRecord.split(",");
//		        	
//		        	final int fov = Integer.parseInt(clusterNextRecordArray[2].replaceAll("\"", ""));
//		        	final int cellId = Integer.parseInt(clusterNextRecordArray[1].replaceAll("\"", ""));
//		        	final int cluster = Integer.parseInt(clusterNextRecordArray[3].replaceAll("\"", ""));
//		        	
//		        	if(cellToPathObjHashMap.containsKey(String.valueOf(cellId)+"_"+String.valueOf(fov))) {
//		        		final PathObject c = cellToPathObjHashMap.get(String.valueOf(cellId)+"_"+String.valueOf(fov));
//			        	final MeasurementList pathObjMeasList = c.getMeasurementList();
//		        		
//			        	pathObjMeasList.put("cosmx:cell:subtype", (double)cluster);  
//		        		
//		        		pathObjMeasList.close();
//			        }
//		        }
		        
		        
		        
		        
		        
		        
			        		
			        		
			        		
			        
			        
			        
			        
			        
			        
//				}
//		        else {    
//			        
//					/*
//		             * Read transcript data
//		             * "transcript_id","cell_id","overlaps_nucleus","feature_name","x_location","y_location","z_location","qv"
//		             */	        
//		        	
//					final String transcriptFilePath = java.nio.file.Paths.get(params.getStringParameterValue("cosmxDir"), "transcripts.csv.gz").toString();
//					
//					final GZIPInputStream transcriptGzipStream = new GZIPInputStream(new FileInputStream(transcriptFilePath));
//					final CSVReader transcriptGzipReader = new CSVReader(new InputStreamReader(transcriptGzipStream));
//					
//					transcriptGzipReader.readNext();
//			        
//			        String[] transcriptNextRecord;
//			        while ((transcriptNextRecord = transcriptGzipReader.readNext()) != null) {
//			        	final double qv = Double.parseDouble(transcriptNextRecord[7]);
//			        	final int overlaps_nucleus = Integer.parseInt(transcriptNextRecord[2]);
//			        	final int cellId = Integer.parseInt(transcriptNextRecord[1]);
//			        	
//			        	if(params.getBooleanParameterValue("transcriptOnNucleusOnly") && overlaps_nucleus == 0) continue;
//			        	if(qv < params.getDoubleParameterValue("qv")) continue;
//			        	if(params.getBooleanParameterValue("transcriptBelongsToCell") && cellId == -1) continue;
//			        	if(!params.getBooleanParameterValue("inclBlankCodeword") && transcriptNextRecord[3].startsWith("BLANK_")) continue;
//			        	if(!params.getBooleanParameterValue("inclNegCtrlCodeword") && transcriptNextRecord[3].startsWith("NegControlCodeword_")) continue;
//			        	if(!params.getBooleanParameterValue("inclNegCtrlProbe") && transcriptNextRecord[3].startsWith("NegControlProbe_")) continue;
//			        	if(!params.getBooleanParameterValue("inclGeneExpr") && !transcriptNextRecord[3].startsWith("BLANK_") && !transcriptNextRecord[3].startsWith("NegControlCodeword_") && !transcriptNextRecord[3].startsWith("NegControlProbe_")) continue;
//			        	
//			        	
//			        			
//			        	final double cx = Double.parseDouble(transcriptNextRecord[4]);
//			        	final double cy = Double.parseDouble(transcriptNextRecord[5]);
//			        	
//			        	final double dx = cx/dapiImagePixelSizeMicrons;
//			        	final double dy = (dapiImageHeightMicrons-cy)/dapiImagePixelSizeMicrons;
//			        	
//			        	final double aX = affineMtx[0] * dx + affineMtx[1] * dy + affineMtx[2] * 1.0;
//			        	final double aY = affineMtx[3] * dx + affineMtx[4] * dy + affineMtx[5] * 1.0;
//			     
//			        	final int fX = (int)Math.round(aX / maskDownsampling);
//			        	final int fY = (int)Math.round(aY / maskDownsampling);
//			        	
//			        	final int cv = pathObjectImageMask.getRGB(fX, fY);
//			        	final int cd0 = cv&0xff;
//			        	final int cd1 = (cv>>8)&0xff;
//			        	final int cd2 = (cv>>16)&0xff;
//						final int cr = cd2*0x10000+cd1*0x100+cd0;
//						
//			        	if(cr != 0) { // This location doesn't have a cell.
//				        	final int pathObjectId = cr - 1;  // pathObjectId starts at 1, since 0 means background
//				        	
//				        	final PathObject cellPathObject = pathObjectList.get(pathObjectId);
//				        	
//				        	final MeasurementList pathObjMeasList = cellPathObject.getMeasurementList();
//		
//				        	if(pathObjMeasList.containsNamedMeasurement("cosmx:cell_transcript:"+transcriptNextRecord[3])) {
//				        		final double transcriptCount = pathObjMeasList.getMeasurementValue("cosmx:cell_transcript:"+transcriptNextRecord[3]);
//				        		pathObjMeasList.putMeasurement("cosmx:cell_transcript:"+transcriptNextRecord[3], transcriptCount+1.0);
//				        	}
//				        	else {
//				        		pathObjMeasList.putMeasurement("cosmx:"+transcriptNextRecord[3], 1.0); 		        
//				        	}
//				        	
//				        	pathObjMeasList.close();
//			        	}
//			        	
//			        	
//			        	
//			        	final int av = annotPathObjectImageMask.getRGB(fX, fY);
//			        	final int ad0 = av&0xff;
//			        	final int ad1 = (av>>8)&0xff;
//			        	final int ad2 = (av>>16)&0xff;
//						final int ar = ad2*0x10000+ad1*0x100+ad0;
//						
//						
//			        	if(ar != 0) { // This location doesn't have a cell.
//				        	final int annotPathObjectId = ar - 1;  // pathObjectId starts at 1, since 0 means background
//				        	
//				        	final PathObject annotPathObject = annotPathObjectList.get(annotPathObjectId);
//				        	
//				        	final MeasurementList annotPathObjMeasList = annotPathObject.getMeasurementList();
//		
//				        	if(annotPathObjMeasList.containsNamedMeasurement("cosmx:spot_transcript:"+transcriptNextRecord[3])) {
//				        		final double transcriptCount = annotPathObjMeasList.getMeasurementValue("cosmx:spot_transcript:"+transcriptNextRecord[3]);
//				        		annotPathObjMeasList.putMeasurement("cosmx:spot_transcript:"+transcriptNextRecord[3], transcriptCount+1.0);
//				        	}
//				        	else {
//				        		annotPathObjMeasList.putMeasurement("cosmx:spot_transcript:"+transcriptNextRecord[3], 1.0); 		        
//				        	}
//				        	
//				        	annotPathObjMeasList.close();
//			        	}        	
//	
//			        	
//			        	
//			        	
//			        	
//			        	
//			        	
//			        	
//			        	
//			        	
//			        	
//			        	
//			        	
//			        }
//			        
//			        transcriptGzipReader.close();
//			
//					
//					
//		        }	
			        
			    
//		        
//		        for(int i = 0; i < posList.size(); i ++) {
//		        	final String id = idList.get(i);
//		        	final Point2D pos = posList.get(i);
//		        	
//		        	final ROI pathRoi = ROIs.createPointsROI(pos.getX(), pos.getY(), null);
//		        	
//		        	final PathClass pathCls = PathClassFactory.getPathClass(id);
//			    	final PathAnnotationObject pathObj = (PathAnnotationObject) PathObjects.createAnnotationObject(pathRoi, pathCls);
//			    	
////					final MeasurementList pathObjMeasList = pathObj.getMeasurementList();
////					pathObjMeasList.close();
//
//			    	pathObjects.add(pathObj);  
//					
//		        	
//		        }
		        hierarchy.getSelectionModel().setSelectedObject(null);
				// QuPathGUI.getInstance().getViewer().setSelectedObject(null);
			}
			catch(Exception e) {	
//				Alert alert = new Alert(AlertType.ERROR);
//				alert.setTitle("Error!");
//				alert.setHeaderText("Something went wrong!");
//				alert.setContentText(e.getMessage());
//
//				alert.showAndWait();
				Dialogs.showErrorMessage("Error", e.getMessage());
				
				lastResults =  "Something went wrong: "+e.getMessage();
				
				return new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
			}				
			
			if (Thread.currentThread().isInterrupted()) {
//				Alert alert = new Alert(AlertType.WARNING);
//				alert.setTitle("Warning!");
//				alert.setHeaderText("Interrupted!");
//				// alert.setContentText(e.getMessage());

				Dialogs.showErrorMessage("Warning", "Interrupted!");
				
				// hierarchy.getSelectionModel().setSelectedObject(null);
				// QuPathGUI.getInstance().getViewer().setSelectedObject(null);
				
				lastResults =  "Interrupted!";
				
				return new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
			}
			
			return resultPathObjectList;
		}
		
		
		@Override
		public String getLastResultsDescription() {
			return lastResults;
		}
		
		
	}

	@Override
	public ParameterList getDefaultParameterList(final ImageData<BufferedImage> imageData) {
		return params;
	}

	@Override
	public String getName() {
		return "Simple tissue detection";
	}

	@Override
	public String getLastResultsDescription() {
		return lastResults;
	}


	@Override
	public String getDescription() {
		return "Detect one or more regions of interest by applying a global threshold";
	}


	@Override
	protected void addRunnableTasks(ImageData<BufferedImage> imageData, PathObject parentObject, List<Runnable> tasks) {
		tasks.add(DetectionPluginTools.createRunnableTask(new AnnotationLoader(), getParameterList(imageData), imageData, parentObject));
	}


	@Override
	protected Collection<? extends PathObject> getParentObjects(final PluginRunner<BufferedImage> runner) {	
		PathObjectHierarchy hierarchy = getHierarchy(runner);
		if (hierarchy.getTMAGrid() == null)
			return Collections.singleton(hierarchy.getRootObject());
		
		return hierarchy.getSelectionModel().getSelectedObjects().stream().filter(p -> p.isTMACore()).collect(Collectors.toList());
	}


	@Override
	public Collection<Class<? extends PathObject>> getSupportedParentObjectClasses() {
		// TODO: Re-allow taking an object as input in order to limit bounds
		// Temporarily disabled so as to avoid asking annoying questions when run repeatedly
//		List<Class<? extends PathObject>> list = new ArrayList<>();
//		list.add(TMACoreObject.class);
//		list.add(PathRootObject.class);
//		return list;
		return Arrays.asList(
				PathAnnotationObject.class,
				TMACoreObject.class
				);		
	}

}
