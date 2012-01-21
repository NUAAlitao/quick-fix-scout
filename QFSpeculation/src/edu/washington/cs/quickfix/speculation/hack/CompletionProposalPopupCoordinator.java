package edu.washington.cs.quickfix.speculation.hack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jdt.ui.text.java.IJavaCompletionProposal;
import org.eclipse.jdt.ui.text.java.IProblemLocation;
import org.eclipse.jface.text.contentassist.CompletionProposalPopup;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

import edu.washington.cs.quickfix.speculation.Speculator;
import edu.washington.cs.quickfix.speculation.calc.SpeculationCalculator;
import edu.washington.cs.quickfix.speculation.calc.model.AugmentedCompletionProposal;
import edu.washington.cs.quickfix.speculation.model.SpeculationUtility;
import edu.washington.cs.synchronization.ProjectSynchronizer;
import edu.washington.cs.util.eclipse.QuickFixUtility;
import edu.washington.cs.util.eclipse.model.Squiggly;

//@formatter:off
/*
 * Facts:
 * 1-) Eclipse loads proposal information on demand. If there are a lot of proposals, however only some of them are visible in the popup,
 * then information for the non-visible quick fixes are not added to the popup unless the user scrolls down.
 * 2-) I have modified this fact. I use a new hook to Eclipse's load and select methods so that I load all the available proposals right away.
 * 3-) Don't use any logging with the methods that Eclipse UI thread will call to create the popup menu, add proposals etc., because
 * due to synchronization of logging (I think) it blocks the UI thread until the whole computation is done (weird !..)
 */
//@formatter:on
public class CompletionProposalPopupCoordinator
{
    private static final CompletionProposalPopupCoordinator instance_ = new CompletionProposalPopupCoordinator();
    private Table table_ = null;
    private CompletionProposalPopup popup_ = null;
    private ArrayList <ICompletionProposal> tableProposals_ = new ArrayList <ICompletionProposal>();
    // all proposals also include the global best proposals.
    private AugmentedCompletionProposal [] localProposals_ = null;
    private AugmentedCompletionProposal [] calculatedProposals_ = null;
    private IJavaCompletionProposal [] eclipseProposals_ = null;
    // Compilation error indicates the locations of the selected quick fix. It can be null
    // if the user invokes quick fix where no compilation error is present.
    // or sometimes Eclipse generates multiple compilation errors on the same line (or same error location),
    // so it can be more than one.
    private Squiggly [] compilationErrors_ = null;
    // best proposals also include the local bests. BP = GBP + LBP.
    private ArrayList <AugmentedCompletionProposal> bestProposals_;
    private ArrayList <AugmentedCompletionProposal> globalBestProposals_;
    private Squiggly [] originalCompilationErrors_;
    private final Object lock_ = new Object();
    private static final Logger logger = Logger.getLogger(CompletionProposalPopupCoordinator.class.getName());
    static
    {
        logger.setLevel(Level.INFO);
        // logger.setLevel(Level.FINER);
        // logger.setLevel(Level.FINEST);
    }

    // singleton
    private CompletionProposalPopupCoordinator()
    {
        bestProposals_ = new ArrayList <AugmentedCompletionProposal>();
        globalBestProposals_ = new ArrayList <AugmentedCompletionProposal>();
    }

    public static synchronized CompletionProposalPopupCoordinator getCoordinator()
    {
        return instance_;
    }

    private AugmentedCompletionProposal [] getAllProposals()
    {
        AugmentedCompletionProposal [] result = null;
        synchronized (lock_)
        {
            result = localProposals_;
        }
        return result;
    }

    public void setBestProposals(ArrayList <AugmentedCompletionProposal> bestProposals)
    {
        /*
         * For some reason if I synchronized the whole calculation, it deadlocks Eclipse UI thread.
         */
        synchronized (lock_)
        {
            bestProposals_ = bestProposals;
            logger.finer("Setting best proposals.");
            for (AugmentedCompletionProposal proposal: bestProposals_)
                logger.finest(proposal.toString());
        }
        if (getAllProposals() != null)
        {
            constructLocalProposalsInternally();
            updateProposalTableInternalInUIThread();
        }
        else
            logger.finer("All proposals are null, not updating the UI.");
    }

    public void setOriginalCompilationErrors(Squiggly [] errors)
    {
        synchronized (lock_)
        {
            originalCompilationErrors_ = errors;
        }
    }

    public void clearBestProposals()
    {
        synchronized (lock_)
        {
            logger.finer("Clearing best proposals.");
            bestProposals_.clear();
        }
    }

    public void updateProposalTable(IJavaCompletionProposal [] eclipseProposals,
            AugmentedCompletionProposal [] calculatedProposals, Squiggly [] compilationErrors)
    {
        logger.finer("Updating proposal table. EclipseProposals.length = "
                + (eclipseProposals == null ? 0 : eclipseProposals.length) + ", augmentedProposals.length = "
                + (calculatedProposals == null ? 0 : calculatedProposals.length));
        while (shouldWait())
        {
            try
            {
                synchronized (this)
                {
                    wait();
                }
            }
            catch (InterruptedException e)
            {
                e.printStackTrace();
            }
        }
        // This might get out before all proposals are set by the Eclipse...
        // Too bad, we are too late. Popup is closed.
        if (!isCurrentPopupActive())
        {
            logger.finer("Current popup is no longer active, not updating the UI.");
            return;
        }
        eclipseProposals_ = eclipseProposals;
        calculatedProposals_ = calculatedProposals;
        // Compilation errors can be none if the user invokes a quick fix where there is no compilation error
        // is present. At this moment only the global best proposals should be shown (if any).
        compilationErrors_ = compilationErrors;
        logger.finer("Received compilation errors:");
        for (Squiggly compilationError: compilationErrors_)
            logger.finer(compilationError.toString());
        constructLocalProposalsInternally();
        updateProposalTableInternalInUIThread();
    }

    private void calculateGBPs()
    {
        synchronized (lock_)
        {
            HashSet <String> currentDisplayStrings = new HashSet <String>();
            for (AugmentedCompletionProposal proposal: calculatedProposals_)
                currentDisplayStrings.add(proposal.getDisplayString());
            globalBestProposals_.clear();
            for (AugmentedCompletionProposal proposal: bestProposals_)
            {
                if (!currentDisplayStrings.contains(proposal.getDisplayString()))
                {
                    boolean fixesAtLeastOne = false;
                    for (Squiggly compilationError: compilationErrors_)
                    {
                        if (proposal.canFix(compilationError))
                            fixesAtLeastOne = true;
                    }
                    if (fixesAtLeastOne)
                        globalBestProposals_.add(proposal);
                }
            }
        }
    }

    // TODO proposals are completely contained by augmented proposals. Not needed.
    // This todo does not make sense, I wonder if I tried to mean eclipseProposals are contained by
    // augmentedProposals?
    private void constructLocalProposalsInternally()
    {
        synchronized (lock_)
        {
            calculateGBPs();
            int calculatedProposalsSize = calculatedProposals_.length;
            localProposals_ = new AugmentedCompletionProposal [calculatedProposalsSize];
            for (int a = 0; a < localProposals_.length; a++)
            {
                AugmentedCompletionProposal calculatedProposal = calculatedProposals_[a];
                Squiggly [] errorAfter = calculatedProposal.getRemainingErrors();
                int errorBefore = calculatedProposal.getErrorBefore();
                ICompletionProposal eclipseProposal = (SpeculationCalculator.TEST_TRANSFORMATION) ? calculatedProposal
                        .getProposal() : null;
                if (eclipseProposal == null)
                {
                    for (ICompletionProposal eclipseProp: eclipseProposals_)
                    {
                        if (eclipseProp.getDisplayString().equals(calculatedProposal.getDisplayString()))
                            eclipseProposal = eclipseProp;
                    }
                }
                assert eclipseProposal != null: "Couldn't find the corresponding eclipse proposal for calculated proposal: "
                        + calculatedProposal;
                // For eclipse proposals, it is okay to pass 'null' as compilation error since we are only using
                // location for
                // best proposals.
                localProposals_[a] = new AugmentedCompletionProposal(eclipseProposal, null, errorAfter, errorBefore);
            }
            Arrays.sort(localProposals_);
            logger.finest("Constructed the following modified strings for the invoked quick fix:");
            for (AugmentedCompletionProposal proposal: localProposals_)
                logger.finest(proposal.toString());
        }
    }

    private void updateProposalTableInternalInUIThread()
    {
        if (!isCurrentPopupActive())
            return;
        Display.getDefault().syncExec(new Runnable()
        {
            @Override
            public void run()
            {
                updateProposalTableInternal();
            }
        });
    }

    private void updateProposalTableInternal()
    {
        if (!isCurrentPopupActive())
        {
            logger.finer("Current popup is no longer active, not updating the UI.");
            return;
        }
        synchronized (lock_)
        {
            table_.setRedraw(false);
            tableProposals_.clear();
            TableItem [] items = table_.getItems();
            int knownStyle = items.length > 0 ? items[0].getStyle(): -1;
            // Here we need to decide which proposals are not included by our computation.
            // This is non-trivial, since the calculation re-ordered the proposals.
            ICompletionProposal [] nonProcessedProposals = getNonProcessedProposals(items);
            HashSet <String> addedProposals = new HashSet <String>();
            // First enter the global best proposals.
            int gbpSize = 0;
            for (AugmentedCompletionProposal globalBestProposal: globalBestProposals_)
            {
                try
                {
                    resolve(globalBestProposal);
                    if (!addedProposals.contains(globalBestProposal.getDisplayString()))
                    {
                        logger.finest("Adding proposal: " + globalBestProposal.getDisplayString() + " as GBP.");
                        addedProposals.add(globalBestProposal.getDisplayString());
                        setTableItem(globalBestProposal, gbpSize, knownStyle, true);
                        gbpSize++;
                    }
                }
                catch (GBPResolutionException e)
                {
                    logger.log(Level.INFO, "Cannot resolve global best proposal for shadow proposal = "
                            + globalBestProposal.getDisplayString(), e);
                }
            }
            // Then, enter the local proposals ordered.
            int localProposalSize = 0;
            for (AugmentedCompletionProposal localProposal: localProposals_)
            {
                if (!addedProposals.contains(localProposal.getDisplayString()))
                {
                    logger.finest("Adding proposal: " + localProposal.getDisplayString() + " as local proposal.");
                    addedProposals.add(localProposal.getDisplayString());
                    setTableItem(localProposal, localProposalSize + gbpSize, knownStyle, false);
                    localProposalSize ++;
                }
            }
            // Then, enter the proposals that we don't have a calculation for.
            int nonProcessedProposalSize = 0;
            for (ICompletionProposal nonProcessedProposal: nonProcessedProposals)
            {
                if (!addedProposals.contains(nonProcessedProposal.getDisplayString()))
                {
                    logger.finest("Adding proposal: " + nonProcessedProposal.getDisplayString() + " as non processed proposal.");
                    addedProposals.add(nonProcessedProposal.getDisplayString());
                    setTableItem(nonProcessedProposal, nonProcessedProposalSize + gbpSize + localProposalSize, knownStyle);
                    nonProcessedProposalSize ++;
                }
            }
            table_.setItemCount(nonProcessedProposalSize + gbpSize + localProposalSize);
            table_.setRedraw(true);
            table_.redraw();
        }
    }

    // Since the resolution from shadow proposals to original proposals are no longer done at the end of
    // speculative analysis, we need to resolve the remaining global best proposals the momment quick fix
    // dialog is created.
    private void resolve(AugmentedCompletionProposal globalBestProposal)
    {
        Squiggly [] originalErrors = null;
        synchronized (lock_)
        {
            originalErrors = originalCompilationErrors_;
        }
        Squiggly shadowCompilationError = globalBestProposal.getRecentCompilationError();
        IProblemLocation shadowLocation = shadowCompilationError.getLocation();
        Squiggly originalCompilationError = null;
        for (Squiggly originalError: originalErrors)
        {
            IProblemLocation originalLocation = originalError.getLocation();
            if (SpeculationUtility.sameProblemLocationContent(shadowLocation, originalLocation))
                originalCompilationError = originalError;
        }
        if (originalCompilationError == null)
            throw new GBPResolutionException("Cannot resolve the original compilation error for shadow proposal = "
                    + globalBestProposal);
        try
        {
            IJavaCompletionProposal [] originalProposals = computeOriginalProposals(originalCompilationError);
            IJavaCompletionProposal originalProposal = findOriginalProposal(originalProposals,
                    globalBestProposal.getProposal());
            globalBestProposal.setProposal(originalProposal);
        }
        catch (Exception e)
        {
            throw new GBPResolutionException("Cannot resolve original proposal for shadow proposal = "
                    + globalBestProposal);
        }
    }

    private void setTableItem(AugmentedCompletionProposal proposal, int index, int knownStyle, boolean gbp)
    {
        TableItem item = (table_.getItemCount() > index) ? table_.getItem(index) : null;
        // This can happen due to newly added items.
        if (item == null)
            item = new TableItem(table_, knownStyle, index);
        tableProposals_.add(proposal.getProposal());
        proposal.setYourselfAsTableItem(item, gbp);
    }

    private TableItem setTableItem(ICompletionProposal proposal, int index, int knownStyle)
    {
        TableItem item = (table_.getItemCount() > index) ? table_.getItem(index) : null;
        // This can happen due to newly added items.
        if (item == null)
            item = new TableItem(table_, knownStyle, index);
        tableProposals_.add(proposal);
        item.setData(proposal);
        String displayInformation = proposal.getDisplayString();
        if (!displayInformation.startsWith("(N/A) "))
            // displayInformation = "(2) " + displayInformation;
            displayInformation = "(N/A) " + displayInformation;
        item.setText(displayInformation);
        return item;
    }

    // The caller of this method must run in Eclipse UI thread (due to the access on TableItem).
    // This method is just written as a helper for updateProposalTableInternal() method.
    private ICompletionProposal [] getNonProcessedProposals(TableItem [] items)
    {
        logger.finest("Table items:");
        for (int a = 0; a < items.length; a++)
            logger.finest((a + 1) + "-) " + items[a].getText());
        logger.finest("All proposals:");
        for (int a = 0; a < localProposals_.length; a++)
            logger.finest((a + 1) + "-) " + localProposals_[a].getDisplayString());
        ArrayList <ICompletionProposal> result = new ArrayList <ICompletionProposal>();
        for (TableItem item: items)
        {
            Object data = item.getData();
            assert data instanceof ICompletionProposal: "Got a proposal that is not iCompletionProposal. proposal.getClass() = "
                    + data.getClass();
            ICompletionProposal proposal = (ICompletionProposal) data;
            String displayInformation = proposal.getDisplayString();
            boolean found = false;
            for (AugmentedCompletionProposal prop: localProposals_)
            {
                if (prop.getDisplayString().equals(displayInformation))
                    found = true;
            }
            if (!found)
                result.add(proposal);
        }
        return result.toArray(new ICompletionProposal [result.size()]);
    }

    public boolean shouldWait()
    {
        synchronized (lock_)
        {
            // logger.finer("currentTable_ == null => " + (table_ == null));
            // logger.finer("currentPopup_ == null => " + (popup_ == null));
            return table_ == null || popup_ == null;
        }
    }

    public void propsoalTableSet(Table proposalTable, CompletionProposalPopup popup)
    {
        // logger.fine("Eclipse notification: proposal table is set!");
        synchronized (lock_)
        {
            table_ = proposalTable;
            popup_ = popup;
            tableProposals_.clear();
            localProposals_ = null;
            calculatedProposals_ = null;
            eclipseProposals_ = null;
        }
    }

    // TODO Make sure that this really returns 'null' if the table has not been modified yet.
    public ICompletionProposal getSelectedProposal(int index)
    {
        synchronized (lock_)
        {
            /*
             * This is a bug fix for Windows. For some reason, in windows, this method gets called even before the
             * dialog is created and with index = -1. If I don't do this, I get an internal ArrayOutOfBoundsException.
             */
            if (index < 0 || tableProposals_.size() <= index)
                return null;
            else
                return tableProposals_.get(index);
        }
    }

    public void popupClosed()
    {
        clear();
    }

    private boolean isCurrentPopupActive()
    {
        synchronized (lock_)
        {
            return popup_ != null && popup_.isActive();
        }
    }

    /*
     * Eclipse only updates table elements on demand! (as they are visible on the screen!). Need to find another way to
     * get the elements.
     */
    public void tableUpdated()
    {
        synchronized (lock_)
        {
            logger.fine("Communication: table updated message is received.");
            if (localProposals_ != null)
                updateProposalTableInternal();
            else
            {
                synchronized (this)
                {
                    notifyAll();
                }
            }
        }
    }

    private void clear()
    {
        synchronized (lock_)
        {
            // This method cannot assign Eclipse proposals and calculated proposals to 'null'
            // as the same quick fix can be called multiple times for the same computation.
            localProposals_ = null;
            table_ = null;
            popup_ = null;
        }
    }

    public void proposalSelected(ICompletionProposal proposal)
    {
        /*
         * If the speculator plug-in disabled, then current synchronizer returns null and we have to check this to
         * prevent NullPointerException. If thrown, it prevents Eclipse to apply the proposal selected to the project
         * when the speculator plug-in is disabled. Bug fix for v.0.6.1
         */
        ProjectSynchronizer synchronizer = Speculator.getSpeculator().getCurrentSynchronizer();
        if (synchronizer != null)
            synchronizer.getTaskWorker().bypassTypingSessionCheck();
        clear();
        /*
         * TODO Somehow invalidate quick fix calculator at this point. Otherwise if user clicks the quick fix too fast
         * (i.e., before the next calculation starts), grabber reads the old values and thinks that it has the results!
         */
    }

    private IJavaCompletionProposal findOriginalProposal(IJavaCompletionProposal [] originalProposals,
            ICompletionProposal shadowProposal) throws Exception
    {
        for (IJavaCompletionProposal proposal: originalProposals)
        {
            if (proposal.getDisplayString().equals(shadowProposal.getDisplayString()))
                return proposal;
        }
        logger.info("Cannot find the corresponding original proposal for = " + shadowProposal.getDisplayString());
        int counter = 0;
        for (IJavaCompletionProposal originalProposal: originalProposals)
        {
            counter++;
            logger.info("\tOriginal proposal #" + counter + " = " + originalProposal.getDisplayString());
        }
        throw new Exception();
    }

    private IJavaCompletionProposal [] computeOriginalProposals(Squiggly originalCE) throws Exception
    {
        return QuickFixUtility.computeQuickFix(originalCE);
    }
}
