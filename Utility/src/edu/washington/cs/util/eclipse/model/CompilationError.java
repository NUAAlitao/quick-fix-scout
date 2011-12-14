package edu.washington.cs.util.eclipse.model;

import java.util.Scanner;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.CorrectionEngine;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaModelMarker;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.internal.ui.text.correction.ProblemLocation;
import org.eclipse.jdt.ui.text.java.IProblemLocation;

@SuppressWarnings("restriction")
public class CompilationError
{
    private static final Logger logger = Logger.getLogger(CompilationError.class.getName());
    static
    {
        logger.setLevel(Level.INFO);
    }
    
    public static final CompilationError [] UNKNOWN = new CompilationError [0];
    // NOT_COMPUTED is a constant type compilation error for flagged proposals.
    public static final CompilationError [] NOT_COMPUTED = new CompilationError[0];
    
    private final IMarker marker_;
    private final IProblemLocation location_;
    private final ICompilationUnit compilationUnit_;
    
    // Lazily computed field.
    private CompilationErrorDetails details_;
 
    public CompilationError(IMarker marker)
    {
        marker_ = marker;
        compilationUnit_ = getCompilationUnitFromMarker(marker_);
        location_ = convertMarkerToProblemLocation(marker_, compilationUnit_);
        details_ = null;
    }
    
    public CompilationErrorDetails computeDetails()
    {
        if (details_ != null)
            return details_;
        
        assert location_ != null: "To compute compilation error details, its location must be non-null.";
        
        int offset = location_.getOffset();
        int line = -1;
        int position = -1;
        assert marker_.getResource() instanceof IFile: "Compilation error markers must be owned by an iFile.";
        IFile file = (IFile) marker_.getResource();
        try 
        {
            Scanner reader = new Scanner(file.getContents());
            line = 1;
            while (reader.hasNext())
            {
                String text = reader.nextLine();
                offset -= text.length();
                if (offset < 0)
                {
                    position = offset + text.length();
                    break;
                }
                line ++;
            }
            reader.close();
        } 
        catch (CoreException e) 
        {
            e.printStackTrace();
        }
        details_ = new CompilationErrorDetails(file, line, position);
        return details_;
    }
    
    public IMarker getMarker()
    {
        return marker_;
    }
    
    public IProblemLocation getLocation()
    {
        return location_;
    }
    
    public ICompilationUnit getCompilationUnit()
    {
        return compilationUnit_;
    }
    
    public IResource getResource()
    {
        return marker_ == null ? null : marker_.getResource();
    }
    
    public String toString()
    {
        return marker_.getResource().getName() + ":" + location_.getOffset();
    }
    
    /***************
     * PRIVATE API *
     **************/
    
    /**
     * Converts the given marker to a corresponding {@link IProblemLocation} <br>
     * Note: This method is copied (and slightly changed) from the source code of Eclipse. TODO Mention where it is
     * taken from.
     * 
     * @param marker Problem marker that will be converted to a problem location.
     * @return The problem location correspondence of the given marker.
     */
    private IProblemLocation convertMarkerToProblemLocation(IMarker marker, ICompilationUnit compilationUnit)
    {
        try
        {
            int id = marker.getAttribute(IJavaModelMarker.ID, -1);
            int start = marker.getAttribute(IMarker.CHAR_START, -1);
            int end = marker.getAttribute(IMarker.CHAR_END, -1);
            int severity = marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
            String [] arguments = CorrectionEngine.getProblemArguments(marker);
            String markerType = marker.getType();
            if (compilationUnit != null && id != -1 && start != -1 && end != -1 && arguments != null)
            {
                boolean isError = (severity == IMarker.SEVERITY_ERROR);
                return new ProblemLocation(start, end - start, id, arguments, isError, markerType);
            }
            else
            {
                String log = "Cannot create problem location from marker = " + marker;
                log += "\ncu = " + (compilationUnit == null ? "null" : compilationUnit.getResource().getProjectRelativePath());
                log += "\nid = " + id;
                log += "\nstart = " + start;
                log += "\nend = " + end;
                log += "\nseverity = " + severity;
                logger.warning(log);
            }
        }
        catch (CoreException e)
        {
            logger.log(Level.SEVERE, "Cannot create problem location from marker: " + marker, e);
        }
        return null;
    }

    /**
     * Returns the compilation unit that contains the given marker.
     * 
     * @param marker The marker that will be used to retrieve the compilation unit that contains it.
     * @return The compilation unit that contains the given marker.
     */
    private ICompilationUnit getCompilationUnitFromMarker(IMarker marker)
    {
        IResource resource = marker.getResource();
        IJavaElement javaElement = JavaCore.create(resource);
        ICompilationUnit cu = null;
        if (javaElement instanceof ICompilationUnit)
            cu = (ICompilationUnit) javaElement;
        return cu;
    }
    
    // This code is copied and modified from ProblemLocation.java in org.eclipse.jdt.internal.ui.text.correction
    public String getErrorCode()
    {
        int code = location_.getProblemId();
        StringBuffer buf = new StringBuffer();
        if ((code & IProblem.TypeRelated) != 0)
        {
            buf.append("TypeRelated + "); //$NON-NLS-1$
        }
        if ((code & IProblem.FieldRelated) != 0)
        {
            buf.append("FieldRelated + "); //$NON-NLS-1$
        }
        if ((code & IProblem.ConstructorRelated) != 0)
        {
            buf.append("ConstructorRelated + "); //$NON-NLS-1$
        }
        if ((code & IProblem.MethodRelated) != 0)
        {
            buf.append("MethodRelated + "); //$NON-NLS-1$
        }
        if ((code & IProblem.ImportRelated) != 0)
        {
            buf.append("ImportRelated + "); //$NON-NLS-1$
        }
        if ((code & IProblem.Internal) != 0)
        {
            buf.append("Internal + "); //$NON-NLS-1$
        }
        if ((code & IProblem.Syntax) != 0)
        {
            buf.append("Syntax + "); //$NON-NLS-1$
        }
        if ((code & IProblem.Javadoc) != 0)
        {
            buf.append("Javadoc + "); //$NON-NLS-1$
        }
        buf.append(code & IProblem.IgnoreCategoriesMask);
        return buf.toString();
    }
}
