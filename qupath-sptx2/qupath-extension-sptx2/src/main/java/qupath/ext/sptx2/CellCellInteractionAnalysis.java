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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
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
import javafx.beans.property.IntegerProperty;
import javafx.geometry.Point2D;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import qupath.imagej.tools.IJTools;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.measure.ObservableMeasurementTableData;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.measurements.Measurement;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectConnections;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.PathRootObject;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.classes.PathClassFactory;
import qupath.lib.objects.PathDetectionObject;
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
public class CellCellInteractionAnalysis extends AbstractDetectionPlugin<BufferedImage> {
	final private static Logger logger = LoggerFactory.getLogger(XeniumAnnotation.class);
	final private StringProperty CCIAnalLRPFileProp = PathPrefs.createPersistentPreference("CCIAnalLRPFile", ""); 
	final private StringProperty CCIAnalVendorProp = PathPrefs.createPersistentPreference("CCIAnalVendor", ""); 
	// final private StringProperty CCIAnalSummaryProp = PathPrefs.createPersistentPreference("CCIAnalOper", "mean"); 

	private final List<String> summaryApproachList = new ArrayList<>(List.of("sum", "mean", "max"));

	
	private ParameterList params;

	final private List<String> vendorlList = Arrays.asList("xenium", "cosmx");
	
	private String lastResults = null;
	
	/**
	 * Constructor.
	 */
	public CellCellInteractionAnalysis() {
		params = new ParameterList()
			.addTitleParameter("Cell-Cell Interaction Analysis")
			.addStringParameter("lrpfile", "Ligand-Receptor Pair list file", CCIAnalLRPFileProp.get(), "Ligand-Receptor Pair list file")
			.addChoiceParameter("vendor", "Vendor", CCIAnalVendorProp.get(), vendorlList, "Choose the vendor that should be used for object classification")
			
			// .addChoiceParameter("summary", "summary approach", CCIAnalSummaryProp.get(), summaryApproachList, "Summary")
			;
	}
	
	class AnnotationLoader implements ObjectDetector<BufferedImage> {
		
		
		
		@Override
		public Collection<PathObject> runDetection(final ImageData<BufferedImage> imageData, final ParameterList params, final ROI pathROI) throws IOException {
			CCIAnalLRPFileProp.set(params.getStringParameterValue("lrpfile"));
			CCIAnalVendorProp.set((String)params.getChoiceParameterValue("vendor"));
			// CCIAnalSummaryProp.set(params.getChoiceParameterValue("summary").toString());
			
			final PathObjectHierarchy hierarchy = imageData.getHierarchy();
			final ObservableMeasurementTableData model = new ObservableMeasurementTableData();
			model.setImageData(imageData, imageData == null ? Collections.emptyList() : hierarchy.getObjects(null, PathDetectionObject.class));
			final PathObjectConnections connections = (PathObjectConnections) imageData.getProperty("OBJECT_CONNECTIONS");
			
			try {
				/*
	             * Generate cell masks with their labels
	             */
				
				final List<PathObject> selectedAnnotationPathObjectList = new ArrayList<>();
				
				for (PathObject pathObject : hierarchy.getSelectionModel().getSelectedObjects()) {
					if (pathObject.isAnnotation() && pathObject.hasChildren())
						selectedAnnotationPathObjectList.add(pathObject);
				}	
				
				if(selectedAnnotationPathObjectList.isEmpty()) throw new Exception("Missed selected annotations");
				
				
				final List<String> availGeneList = model.getAllNames().stream().filter(c -> c.startsWith((String)params.getChoiceParameterValue("vendor")+":cell_transcript:")).collect(Collectors.toList());
				
				final List<List<String>> lrpList = new ArrayList<>();
				
				final String lprFilePath = params.getStringParameterValue("lrpfile");
				final FileReader lrpFileReader = new FileReader(new File(lprFilePath));
				final BufferedReader lrpReader = new BufferedReader(lrpFileReader);
				lrpReader.readLine();
				String lrpNextRecord;
				
				while ((lrpNextRecord = lrpReader.readLine()) != null) {
		        	final String[] lrpNextRecordArray = lrpNextRecord.split(",");
		        	final String ligand = lrpNextRecordArray[1].replaceAll("\"", "");
		        	final String receptor = lrpNextRecordArray[2].replaceAll("\"", "");
		        		
		        	if(availGeneList.contains((String)params.getChoiceParameterValue("vendor")+":cell_transcript:"+ligand) && availGeneList.contains((String)params.getChoiceParameterValue("vendor")+":cell_transcript:"+receptor))
		        		lrpList.add(Arrays.asList(ligand, receptor));
				}
				
				lrpReader.close();
				
				selectedAnnotationPathObjectList.parallelStream().forEach(p -> {
				// for(PathObject p: selectedAnnotationPathObjectList) {
					// for(PathObject c: p.getChildObjects()) {
					p.getChildObjects().parallelStream().forEach(c -> { 
						final List<PathObject> connectedObj = connections.getConnections(c);
						final MeasurementList cMeasList = c.getMeasurementList();
						final List<String> cgList = cMeasList.getMeasurementNames().stream().filter(g -> g.startsWith((String)params.getChoiceParameterValue("vendor")+":cell_transcript:")).collect(Collectors.toList());
						
//						if(lrpList.stream().map(g -> cMeasList.get((String)params.getChoiceParameterValue("vendor")+":cell_transcript:"+g.get(0))).anyMatch(g -> g.isNaN())) continue;
//						if(cgList.stream().map(g -> cMeasList.get(g)).anyMatch(g -> g.isNaN())) continue;
						if(lrpList.stream().map(g -> cMeasList.get((String)params.getChoiceParameterValue("vendor")+":cell_transcript:"+g.get(0))).anyMatch(g -> g.isNaN())) return;
						if(cgList.stream().map(g -> cMeasList.get(g)).anyMatch(g -> g.isNaN())) return;
						
						final double cgSum = cgList.stream().map(g -> cMeasList.get(g)).mapToDouble(Double::doubleValue).sum();
						final Map<String, Double> cgMap = cgList.stream().collect(Collectors.toMap(g -> g, g -> cMeasList.get(g)/cgSum));
						
						lrpList.stream().forEach(g -> {cMeasList.put((String)params.getChoiceParameterValue("vendor")+":cell_cci:"+g.get(0)+"_"+g.get(1), 0);});
						
						// for(PathObject d: connectedObj) {
						connectedObj.parallelStream().forEach(d -> {
							final MeasurementList dMeasList = d.getMeasurementList();
							final List<String> dgList = dMeasList.getMeasurementNames().stream().filter(g -> g.startsWith((String)params.getChoiceParameterValue("vendor")+":cell_transcript:")).collect(Collectors.toList());
							
//							if(lrpList.stream().map(g -> dMeasList.get((String)params.getChoiceParameterValue("vendor")+":cell_transcript:"+g.get(1))).anyMatch(g -> g.isNaN())) continue;
//							if(dgList.stream().map(g -> dMeasList.get(g)).anyMatch(g -> g.isNaN())) continue;
							if(lrpList.stream().map(g -> dMeasList.get((String)params.getChoiceParameterValue("vendor")+":cell_transcript:"+g.get(1))).anyMatch(g -> g.isNaN())) return;
							if(dgList.stream().map(g -> dMeasList.get(g)).anyMatch(g -> g.isNaN())) return;
							
							final double dgSum = dgList.stream().map(g -> dMeasList.get(g)).mapToDouble(Double::doubleValue).sum();
							final Map<String, Double> dgMap = dgList.stream().collect(Collectors.toMap(g -> g, g -> dMeasList.get(g)/dgSum));
							
							// for(List<String> lrp: lrpList) {
							lrpList.parallelStream().forEach(lrp -> {
								final Double cv = cgMap.get((String)params.getChoiceParameterValue("vendor")+":cell_transcript:"+lrp.get(0));
								final Double dv = dgMap.get((String)params.getChoiceParameterValue("vendor")+":cell_transcript:"+lrp.get(1));
								
								// if(cv.isNaN() || dv.isNaN()) continue;
								if(cv.isNaN() || dv.isNaN()) return;
								
								synchronized (cMeasList) {
									final Double ov = cMeasList.get((String)params.getChoiceParameterValue("vendor")+":cell_cci:"+lrp.get(0)+"_"+lrp.get(1));
									
									if(ov.isNaN()) {
										cMeasList.put((String)params.getChoiceParameterValue("vendor")+":cell_cci:"+lrp.get(0)+"_"+lrp.get(1), cv*dv);
									}
									else {
//										if(params.getChoiceParameterValue("summary").equals(summaryApproachList.get(2))) { 
//											if(cv*dv > ov.doubleValue()) {
//												cMeasList.put((String)params.getChoiceParameterValue("vendor")+":cell_cci:"+lrp.get(0)+"_"+lrp.get(1), cv*dv);
//											}
//											
//										}
//										else {
											cMeasList.put((String)params.getChoiceParameterValue("vendor")+":cell_cci:"+lrp.get(0)+"_"+lrp.get(1), ov.doubleValue()+(cv*dv));
//										}
									}
							    }
							// }
							});
							
							dMeasList.close();
						// }
						});
						
//						if(params.getChoiceParameterValue("summary").equals(summaryApproachList.get(1))) { 
							
//							for(List<String> lrp: lrpList) {
//								final Double ov = cMeasList.get((String)params.getChoiceParameterValue("vendor")+":cell_cci:"+lrp.get(0)+"_"+lrp.get(1));
//								
//								synchronized (cMeasList) {
//									if(!ov.isNaN()) cMeasList.put((String)params.getChoiceParameterValue("vendor")+":cell_cci:"+lrp.get(0)+"_"+lrp.get(1), ov/connectedObj.size());
//							    }
//							}
//						}
//						
						cMeasList.close();
					// }
					});
				});
//				}
	            
		        hierarchy.getSelectionModel().setSelectedObject(null);
				
			}
			catch(Exception e) {	

				Dialogs.showErrorMessage("Error", e.getMessage());
				
				lastResults =  "Something went wrong: "+e.getMessage();
				
				return new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
			}				
			
			if (Thread.currentThread().isInterrupted()) {

				Dialogs.showErrorMessage("Warning", "Interrupted!");
				
				
				lastResults =  "Interrupted!";
				
				return new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
			}
			
			return new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
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
