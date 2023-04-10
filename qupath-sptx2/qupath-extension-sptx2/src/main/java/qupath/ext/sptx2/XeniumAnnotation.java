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
import java.awt.Polygon;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;

import javax.imageio.ImageIO;
import javax.swing.JOptionPane;

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
import qupath.lib.objects.classes.PathClassFactory;
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
import org.json.JSONArray;
import org.json.JSONObject;
/**
 * Plugin for loading 10x Visium Annotation 
 * 
 * @author Chao Hui Huang
 *
 */
public class XeniumAnnotation extends AbstractDetectionPlugin<BufferedImage> {
	
	final private static Logger logger = LoggerFactory.getLogger(XeniumAnnotation.class);
	
	final private StringProperty xnumAntnXnumFldrProp = PathPrefs.createPersistentPreference("xnumAntnXnumFldr", ""); 
	
	private ParameterList params;

	private String lastResults = null;
	
	/**
	 * Constructor.
	 */
	public XeniumAnnotation() {
		params = new ParameterList()
			.addTitleParameter("10X Xenium Data Loader")
			.addStringParameter("xeniumDir", "Xenium directory", xnumAntnXnumFldrProp.get(), "Xenium Out Directory")
			.addBooleanParameter("fromTranscriptFile", "Load directly from transcript raw data file? (default: false)", false, "Load data from transcript file directly? (default: false)")
			// .addBooleanParameter("loadClusterData", "Load Cluster Data? (default: false)", true, "Load Cluster Data? (default: false)")		
//			.addBooleanParameter("consolToAnnot", "Consolidate transcript data to Visium-style spots? (default: false)", false, "Consolidate Transcript Data to Annotations? (default: false)")
			.addEmptyParameter("")
			.addBooleanParameter("inclGeneExpr", "Include Gene Expression? (default: true)", true, "Include Gene Expression? (default: true)")		
			.addBooleanParameter("inclBlankCodeword", "Include Blank Codeword? (default: false)", false, "Include Blank Codeword? (default: false)")		
			.addBooleanParameter("inclNegCtrlCodeword", "Include Negative Control Codeword? (default: false)", false, "Include Negative Control Codeword? (default: false)")		
			.addBooleanParameter("inclNegCtrlProbe", "Include Negative Control Probe? (default: false)", false, "Include Negative Control Probe? (default: false)")		
			.addEmptyParameter("")
			.addEmptyParameter("Options for loading raw transcript data")
			.addDoubleParameter("qv", "Minimal Q-Value", 0.0, null, "Minimal Q-Value")		
			.addBooleanParameter("transcriptOnNucleusOnly", "Only the transcripts overlapped on nucleus? (default: true)", true, "Only the transcripts overlapped on nucleus? (default: true)")		
			.addBooleanParameter("transcriptBelongsToCell", "Only the transcripts belongs to a cell (based on DAPI)? (default: true)", true, "Only the transcripts belongs to a cell? (default: true)")		
			.addEmptyParameter("")
			.addIntParameter("maskDownsampling", "Downsampling for transcript to cell assignment", 2, null, "Downsampling for cell-transciptome assignment")			
			;
	}
	
	class AnnotationLoader implements ObjectDetector<BufferedImage> {
		
		@Override
		public Collection<PathObject> runDetection(final ImageData<BufferedImage> imageData, final ParameterList params, final ROI pathROI) throws IOException {
			xnumAntnXnumFldrProp.set(params.getStringParameterValue("xeniumDir"));
			
			final ImageServer<BufferedImage> server = imageData.getServer();				
			final PathObjectHierarchy hierarchy = imageData.getHierarchy();
			final ArrayList<PathObject> resultPathObjectList = new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
			
			try {
				
				final InputStream is = new FileInputStream(Paths.get(params.getStringParameterValue("xeniumDir"), "affine_matrix.json").toString());
				final String jsonTxt = IOUtils.toString(is, "UTF-8");
				final JSONObject jsonObj = new JSONObject(jsonTxt);    
				
				final double dapiImageHeightMicrons = jsonObj.getDouble("dapi_height");
				final double dapiImagePixelSizeMicrons = jsonObj.getDouble("dapi_pixel_size");
				final double[] affineMtx = IntStream.range(0, 6).mapToDouble(i -> jsonObj.getJSONArray("affine_matrix").getDouble(i)).toArray();
					            
		        final double pixelSizeMicrons = server.getPixelCalibration().getAveragedPixelSizeMicrons();
		        
	            /*
	             * Generate cell masks with their labels
	             */
				
				final List<PathObject> selectedAnnotationPathObjectList = new ArrayList<>();
				
				for (PathObject pathObject : hierarchy.getSelectionModel().getSelectedObjects()) {
					if (pathObject.isAnnotation() && pathObject.hasChildren())
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
				
				
				if(params.getStringParameterValue("xeniumDir").isBlank()) throw new Exception("singleCellFile is blank");
				
				final HashMap<Integer, Integer> cellToClusterHashMap = new HashMap<>();
				
				final String clusterFilePath = java.nio.file.Paths.get(params.getStringParameterValue("xeniumDir"), "analysis", "clustering", "gene_expression_graphclust", "clusters.csv").toString();
				final FileReader clusterFileReader = new FileReader(new File(clusterFilePath));
				final BufferedReader clusterReader = new BufferedReader(clusterFileReader);
				clusterReader.readLine();
				String clusterNextRecord;
				
				while ((clusterNextRecord = clusterReader.readLine()) != null) {
		        	final String[] clusterNextRecordArray = clusterNextRecord.split(",");
		        	final int cellId = Integer.parseInt(clusterNextRecordArray[0]);
		        	final int clusterId = Integer.parseInt(clusterNextRecordArray[1]);
		        	cellToClusterHashMap.put(cellId, clusterId);
				}
				
				clusterReader.close();
				
				final HashMap<Integer, PathObject> cellToPathObjHashMap = new HashMap<>();
			
				final String singleCellFilePath = java.nio.file.Paths.get(params.getStringParameterValue("xeniumDir"), "cells.csv.gz").toString();
				final GZIPInputStream singleCellGzipStream = new GZIPInputStream(new FileInputStream(singleCellFilePath));
				final BufferedReader singleCellGzipReader = new BufferedReader(new InputStreamReader(singleCellGzipStream));
				singleCellGzipReader.readLine();
				String singleCellNextRecord;
				
		        while ((singleCellNextRecord = singleCellGzipReader.readLine()) != null) {
		        	final String[] singleCellNextRecordArray = singleCellNextRecord.split(",");
		        	final int cellId = Integer.parseInt(singleCellNextRecordArray[0]);
		        	
		        	final double transcriptCounts = Double.parseDouble(singleCellNextRecordArray[3]);
		        	final double controlProbeCounts = Double.parseDouble(singleCellNextRecordArray[4]);
		        	final double controlCodewordCounts = Double.parseDouble(singleCellNextRecordArray[5]);
		        	final double totalCounts = Double.parseDouble(singleCellNextRecordArray[6]);
		        	final double cellArea = Double.parseDouble(singleCellNextRecordArray[7]);
		        	final double nucleusArea = Double.parseDouble(singleCellNextRecordArray[8]);
		        	
		        	final double cx = Double.parseDouble(singleCellNextRecordArray[1]);
		        	final double cy = Double.parseDouble(singleCellNextRecordArray[2]);
		        	
		        	final double dx = cx/dapiImagePixelSizeMicrons;
		        	final double dy = (dapiImageHeightMicrons-cy)/dapiImagePixelSizeMicrons;
		        	
		        	final double aX = affineMtx[0] * dx + affineMtx[1] * dy + affineMtx[2] * 1.0;
		        	final double aY = affineMtx[3] * dx + affineMtx[4] * dy + affineMtx[5] * 1.0;
		     
		        	final int fX = (int)Math.round(aX / maskDownsampling);
		        	final int fY = (int)Math.round(aY / maskDownsampling);
		        	
		        	if(fX < 0 || fX >= pathObjectImageMask.getWidth() || fY < 0 || fY >=  pathObjectImageMask.getHeight()) continue;
		        	
		        	final int v = pathObjectImageMask.getRGB(fX, fY);
		        	final int d0 = v&0xff;
		        	final int d1 = (v>>8)&0xff;
		        	final int d2 = (v>>16)&0xff;
					final int r = d2*0x10000+d1*0x100+d0;
				    
		        	if(r == 0) continue; // This location doesn't have a cell.
			        	
		        	final int pathObjectId = r - 1;  // pathObjectId starts at 1, since 0 means background
			        	
		        	final PathObject cellPathObject = pathObjectList.get(pathObjectId);
		        	cellToPathObjHashMap.put(cellId, cellPathObject);
		        	
		        	final Integer clusterId = cellToClusterHashMap.get(cellId);
		        	
		        	if(clusterId != null) {
		        		final PathClass pathCls = PathClassFactory.getPathClass("xenium:cluster:"+Integer.toString(clusterId));
			        	cellPathObject.setPathClass(pathCls);
		        	}
		        	
		        	final double roiX = cellPathObject.getROI().getCentroidX();
		        	final double roiY = cellPathObject.getROI().getCentroidY();
		        	final double newDist = (new Point2D(aX, aY).distance(roiX, roiY))*pixelSizeMicrons;
		        	final MeasurementList pathObjMeasList = cellPathObject.getMeasurementList();
		        	if(pathObjMeasList.containsKey("xenium:cell:cell_id")) {
		        		final double minDist = pathObjMeasList.get("xenium:cell:displacement");
		        		if(newDist < minDist) {
		        			pathObjMeasList.put("xenium:cell:cell_id", cellId);
		        			pathObjMeasList.put("xenium:cell:displacement", newDist);
		        			pathObjMeasList.put("xenium:cell:x_centroid", cx);
		        			pathObjMeasList.put("xenium:cell:y_centroid", cy);
		        			if(clusterId != null) pathObjMeasList.put("xenium:cell:cluster_id", clusterId);
		        			pathObjMeasList.put("xenium:cell:transcript_counts", transcriptCounts);
		        			pathObjMeasList.put("xenium:cell:control_probe_counts", controlProbeCounts);
		        			pathObjMeasList.put("xenium:cell:control_codeword_counts", controlCodewordCounts);
		        			pathObjMeasList.put("xenium:cell:total_counts", totalCounts);
		        			pathObjMeasList.put("xenium:cell:cell_area", cellArea);
		        			pathObjMeasList.put("xenium:cell:nucleus_area", nucleusArea);
		        		}
		        	}
		        	else {
		        		pathObjMeasList.put("xenium:cell:cell_id", cellId);
	        			pathObjMeasList.put("xenium:cell:displacement", newDist);
	        			pathObjMeasList.put("xenium:cell:x_centroid", cx);
	        			pathObjMeasList.put("xenium:cell:y_centroid", cy);
	        			if(clusterId != null) pathObjMeasList.put("xenium:cell:cluster_id", clusterId);
	        			pathObjMeasList.put("xenium:cell:transcript_counts", transcriptCounts);
	        			pathObjMeasList.put("xenium:cell:control_probe_counts", controlProbeCounts);
	        			pathObjMeasList.put("xenium:cell:control_codeword_counts", controlCodewordCounts);
	        			pathObjMeasList.put("xenium:cell:total_counts", totalCounts);
	        			pathObjMeasList.put("xenium:cell:cell_area", cellArea);
	        			pathObjMeasList.put("xenium:cell:nucleus_area", nucleusArea);     		        
		        	}
		        	
		        	pathObjMeasList.close(); 
	        	}		        	
	        	
	
	        	
	        	
	        	
	        	
	        	
		        	
		        singleCellGzipReader.close();
				
				
				/*
	             * Read feature matrix data
	             */
					
		        if(!params.getBooleanParameterValue("fromTranscriptFile")) {
					final String barcodeFilePath = java.nio.file.Paths.get(params.getStringParameterValue("xeniumDir"), "cell_feature_matrix", "barcodes.tsv.gz").toString();
					final String featureFilePath = java.nio.file.Paths.get(params.getStringParameterValue("xeniumDir"), "cell_feature_matrix", "features.tsv.gz").toString();
					final String matrixFilePath = java.nio.file.Paths.get(params.getStringParameterValue("xeniumDir"), "cell_feature_matrix", "matrix.mtx.gz").toString();
					
					final GZIPInputStream barcodeGzipStream = new GZIPInputStream(new FileInputStream(barcodeFilePath));
					final BufferedReader barcodeGzipReader = new BufferedReader(new InputStreamReader(barcodeGzipStream));
					
			        final List<Integer> barcodeList = new ArrayList<>();
			        
			        String barcodeNextRecord;
			        while ((barcodeNextRecord = barcodeGzipReader.readLine()) != null) {
			        	barcodeList.add(Integer.parseInt(barcodeNextRecord));
			        }
			        
			        final List<String> featureIdList = new ArrayList<>();
			        final List<String> featureNameList = new ArrayList<>();
			        final List<String> featureTypeList = new ArrayList<>();
			        
					final GZIPInputStream featureGzipStream = new GZIPInputStream(new FileInputStream(featureFilePath));
					final BufferedReader featureGzipReader = new BufferedReader(new InputStreamReader(featureGzipStream));
			        
			        String featureNextRecord;
			        while ((featureNextRecord = featureGzipReader.readLine()) != null) {
			        	final String[] featureNextRecordArray = featureNextRecord.split("\t");
			        	featureIdList.add(featureNextRecordArray[0]);
			        	featureNameList.add(featureNextRecordArray[1]);
			        	featureTypeList.add(featureNextRecordArray[2]);
			        }
			        
			        final GZIPInputStream matrixGzipStream = new GZIPInputStream(new FileInputStream(matrixFilePath));
			        final BufferedReader matrixGzipReader = new BufferedReader(new InputStreamReader(matrixGzipStream), '\t');
					
			        matrixGzipReader.readLine();
			        matrixGzipReader.readLine();
			        matrixGzipReader.readLine();
			        
			        final int[][] matrix = new int[featureNameList.size()][barcodeList.size()];
			        
			        String matrixNextRecord;
			        while ((matrixNextRecord = matrixGzipReader.readLine()) != null) {
			        	final String[] matrixNextRecordArray = matrixNextRecord.split(" ");
			        	final int f = Integer.parseInt(matrixNextRecordArray[0])-1;
			        	final int b = Integer.parseInt(matrixNextRecordArray[1])-1;
			        	final int v = Integer.parseInt(matrixNextRecordArray[2]);
			        	
			        	matrix[f][b] = v;
			        }
			        
			        
			        for(int b = 0; b < barcodeList.size(); b ++) {
			        	if(cellToPathObjHashMap.containsKey(barcodeList.get(b))) {
				        	final PathObject c = cellToPathObjHashMap.get(barcodeList.get(b));
				        	final MeasurementList pathObjMeasList = c.getMeasurementList();
				        	
				        	for(int f = 0; f < featureNameList.size(); f ++) {	
				        		if(!params.getBooleanParameterValue("inclBlankCodeword") && (featureTypeList.get(f).compareTo("Blank Codeword") == 0)) continue;
			        			if(!params.getBooleanParameterValue("inclGeneExpr") && (featureTypeList.get(f).compareTo("Gene Expression") == 0)) continue;
			        			if(!params.getBooleanParameterValue("inclNegCtrlCodeword") && (featureTypeList.get(f).compareTo("Negative Control Codeword") == 0)) continue;
			        			if(!params.getBooleanParameterValue("inclNegCtrlProbe") && (featureTypeList.get(f).compareTo("Negative Control Probe") == 0)) continue;
				        		
			        			pathObjMeasList.put("xenium:cell_transcript:"+featureNameList.get(f), matrix[f][b]);  
				        			 
				        	}
				        	
				        	pathObjMeasList.close();
				        	
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
//				        					parentPathObjMeasList.containsKey("xenium:spot_transcript:"+featureNameList.get(f))? 
//				        					parentPathObjMeasList.get("xenium:spot_transcript:"+featureNameList.get(f)): 
//				        					0.0;
//				        	
//					        		parentPathObjMeasList.put("xenium:spot_transcript:"+featureNameList.get(f), matrix[f][b]+oldVal);  
//					        	}
//				        		
//					        	parentPathObjMeasList.close();
//				        	}
			        	}
			        }
				}
		        else {    
			        
					/*
		             * Read transcript data
		             * "transcript_id","cell_id","overlaps_nucleus","feature_name","x_location","y_location","z_location","qv"
		             */	        
		        	
					final String transcriptFilePath = java.nio.file.Paths.get(params.getStringParameterValue("xeniumDir"), "transcripts.csv.gz").toString();
					
					final GZIPInputStream transcriptGzipStream = new GZIPInputStream(new FileInputStream(transcriptFilePath));
					final CSVReader transcriptGzipReader = new CSVReader(new InputStreamReader(transcriptGzipStream));
					
					transcriptGzipReader.readNext();
			        
			        String[] transcriptNextRecord;
			        while ((transcriptNextRecord = transcriptGzipReader.readNext()) != null) {
			        	final double qv = Double.parseDouble(transcriptNextRecord[7]);
			        	final int overlaps_nucleus = Integer.parseInt(transcriptNextRecord[2]);
			        	final int cellId = Integer.parseInt(transcriptNextRecord[1]);
			        	
			        	if(params.getBooleanParameterValue("transcriptOnNucleusOnly") && overlaps_nucleus == 0) continue;
			        	if(qv < params.getDoubleParameterValue("qv")) continue;
			        	if(params.getBooleanParameterValue("transcriptBelongsToCell") && cellId == -1) continue;
			        	if(!params.getBooleanParameterValue("inclBlankCodeword") && transcriptNextRecord[3].startsWith("BLANK_")) continue;
			        	if(!params.getBooleanParameterValue("inclNegCtrlCodeword") && transcriptNextRecord[3].startsWith("NegControlCodeword_")) continue;
			        	if(!params.getBooleanParameterValue("inclNegCtrlProbe") && transcriptNextRecord[3].startsWith("NegControlProbe_")) continue;
			        	if(!params.getBooleanParameterValue("inclGeneExpr") && !transcriptNextRecord[3].startsWith("BLANK_") && !transcriptNextRecord[3].startsWith("NegControlCodeword_") && !transcriptNextRecord[3].startsWith("NegControlProbe_")) continue;
			        	
			        	
			        			
			        	final double cx = Double.parseDouble(transcriptNextRecord[4]);
			        	final double cy = Double.parseDouble(transcriptNextRecord[5]);
			        	
			        	final double dx = cx/dapiImagePixelSizeMicrons;
			        	final double dy = (dapiImageHeightMicrons-cy)/dapiImagePixelSizeMicrons;
			        	
			        	final double aX = affineMtx[0] * dx + affineMtx[1] * dy + affineMtx[2] * 1.0;
			        	final double aY = affineMtx[3] * dx + affineMtx[4] * dy + affineMtx[5] * 1.0;
			     
			        	final int fX = (int)Math.round(aX / maskDownsampling);
			        	final int fY = (int)Math.round(aY / maskDownsampling);
			        	
			        	final int cv = pathObjectImageMask.getRGB(fX, fY);
			        	final int cd0 = cv&0xff;
			        	final int cd1 = (cv>>8)&0xff;
			        	final int cd2 = (cv>>16)&0xff;
						final int cr = cd2*0x10000+cd1*0x100+cd0;
						
			        	if(cr != 0) { // This location doesn't have a cell.
				        	final int pathObjectId = cr - 1;  // pathObjectId starts at 1, since 0 means background
				        	
				        	final PathObject cellPathObject = pathObjectList.get(pathObjectId);
				        	
				        	final MeasurementList pathObjMeasList = cellPathObject.getMeasurementList();
		
				        	if(pathObjMeasList.containsNamedMeasurement("xenium:cell_transcript:"+transcriptNextRecord[3])) {
				        		final double transcriptCount = pathObjMeasList.getMeasurementValue("xenium:cell_transcript:"+transcriptNextRecord[3]);
				        		pathObjMeasList.putMeasurement("xenium:cell_transcript:"+transcriptNextRecord[3], transcriptCount+1.0);
				        	}
				        	else {
				        		pathObjMeasList.putMeasurement("xenium:"+transcriptNextRecord[3], 1.0); 		        
				        	}
				        	
				        	pathObjMeasList.close();
			        	}
			        	
			        	
			        	
			        	final int av = annotPathObjectImageMask.getRGB(fX, fY);
			        	final int ad0 = av&0xff;
			        	final int ad1 = (av>>8)&0xff;
			        	final int ad2 = (av>>16)&0xff;
						final int ar = ad2*0x10000+ad1*0x100+ad0;
						
						
			        	if(ar != 0) { // This location doesn't have a cell.
				        	final int annotPathObjectId = ar - 1;  // pathObjectId starts at 1, since 0 means background
				        	
				        	final PathObject annotPathObject = annotPathObjectList.get(annotPathObjectId);
				        	
				        	final MeasurementList annotPathObjMeasList = annotPathObject.getMeasurementList();
		
				        	if(annotPathObjMeasList.containsNamedMeasurement("xenium:spot_transcript:"+transcriptNextRecord[3])) {
				        		final double transcriptCount = annotPathObjMeasList.getMeasurementValue("xenium:spot_transcript:"+transcriptNextRecord[3]);
				        		annotPathObjMeasList.putMeasurement("xenium:spot_transcript:"+transcriptNextRecord[3], transcriptCount+1.0);
				        	}
				        	else {
				        		annotPathObjMeasList.putMeasurement("xenium:spot_transcript:"+transcriptNextRecord[3], 1.0); 		        
				        	}
				        	
				        	annotPathObjMeasList.close();
			        	}        	
	
			        	
			        	
			        	
			        	
			        	
			        	
			        	
			        	
			        	
			        	
			        	
			        	
			        }
			        
			        transcriptGzipReader.close();
			
					
					
		        }	
			        
			    
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
