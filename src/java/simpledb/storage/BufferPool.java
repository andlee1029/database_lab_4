package simpledb.storage;

import simpledb.common.Database;

import simpledb.common.Permissions;
import simpledb.common.DbException;
import simpledb.common.DeadlockException;
import simpledb.transaction.TransactionAbortedException;
import simpledb.transaction.TransactionId;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

enum LockLevel{
	SHARED,
	EXCLUSIVE;
}

/**
 * BufferPool manages the reading and writing of pages into memory from
 * disk. Access methods call into it to retrieve pages, and it fetches
 * pages from the appropriate location.
 * <p>
 * The BufferPool is also responsible for locking;  when a transaction fetches
 * a page, BufferPool checks that the transaction has the appropriate
 * locks to read/write the page.
 * 
 * @Threadsafe, all fields are final
 */
public class BufferPool {
	private class LockManager{
		private ConcurrentHashMap<PageId,ConcurrentHashMap<TransactionId, Boolean>> pagetoshared;
		private ConcurrentHashMap<PageId, TransactionId> pagetoexclusive;
		private ConcurrentHashMap<TransactionId,ArrayList<PageId>> transactiontopage;
		private ConcurrentHashMap<TransactionId,ArrayList<TransactionId>> waitinggraph;
		
		private LockManager() {
			this.pagetoshared = new ConcurrentHashMap<PageId,ConcurrentHashMap<TransactionId,Boolean>>();
			this.pagetoexclusive = new ConcurrentHashMap<PageId, TransactionId>();
			this.transactiontopage = new ConcurrentHashMap<TransactionId,ArrayList<PageId>>();
			this.waitinggraph = new ConcurrentHashMap<TransactionId, ArrayList<TransactionId>>();
		}
		private boolean dfs(TransactionId vertex) {
			HashSet<TransactionId> visited = new HashSet<TransactionId>();
			Queue<TransactionId> queue = new LinkedList<TransactionId>();
			queue.add(vertex);
			while(!queue.isEmpty()) {
				TransactionId v = queue.poll();
				if (visited.contains(v)) return false;
				visited.add(v);
				for (TransactionId t : this.waitinggraph.keySet()) {
					if (t == v) {
						ArrayList<TransactionId> neighs = this.waitinggraph.get(t);
						for (TransactionId neigh : neighs) {
							if(!queue.contains(neigh)) queue.add(neigh);
						}
					}
				}
			}
			
			return true;
		}
		
		private boolean checkCycles() {
			ConcurrentHashMap<TransactionId, Boolean> vertices = new ConcurrentHashMap<TransactionId, Boolean>();
			for (TransactionId t: this.waitinggraph.keySet()) {
				vertices.put(t, false);
				for(TransactionId vertex : this.waitinggraph.get(t)) vertices.put(vertex, false);
			}
			
			for (TransactionId vertex : vertices.keySet()) {
				if (this.dfs(vertex)) return true;
			}
			
			return false;
		}
		
		private void insertGraph(TransactionId tid, PageId pid, Permissions perm) throws TransactionAbortedException {
			ArrayList<TransactionId> waiting = new ArrayList<TransactionId>();
			if (perm == Permissions.READ_ONLY) {
				waiting.add(this.pagetoexclusive.get(pid));
			}
			else {
				ConcurrentHashMap<TransactionId, Boolean> shared = this.pagetoshared.get(pid);
				for (TransactionId t : shared.keySet()) {
					if (t != tid) waiting.add(t);
				}
			}
			this.waitinggraph.put(tid, waiting);
			
			if(this.checkCycles()) {
				throw new TransactionAbortedException();
			}
		}
		
		private void deleteGraph(TransactionId tid, PageId pid) {
			this.waitinggraph.remove(tid);
		}
		
		private boolean getShared(TransactionId tid, PageId pid) {
			TransactionId checkextid = this.pagetoexclusive.getOrDefault(pid, null);
			if((checkextid != null) && (checkextid == tid)) return true;
			if (checkextid != null) return false;
			ConcurrentHashMap<TransactionId,Boolean> shared = this.pagetoshared.getOrDefault(pid, null);
			if ((shared == null) || (!shared.getOrDefault(tid, false))) {
				if(shared == null) {
					ConcurrentHashMap<TransactionId,Boolean> temp = new ConcurrentHashMap<TransactionId,Boolean>();
					temp.put(tid, true);
					this.pagetoshared.put(pid, temp);
				}
				else {
					shared.put(tid, true);
				}
				if(!this.transactiontopage.containsKey(tid)) this.transactiontopage.put(tid, new ArrayList<PageId>());
				ArrayList<PageId> pages = this.transactiontopage.get(tid);
				if (!pages.contains(pid)) pages.add(pid);
				return true;
				
			}
			return true;
		}
		private boolean getExclusive(TransactionId tid, PageId pid) {
			TransactionId checkextid = this.pagetoexclusive.getOrDefault(pid, null);
			if((checkextid != null) && (checkextid == tid)) return true;
			if (checkextid != null) return false;
			ConcurrentHashMap<TransactionId,Boolean> shared = this.pagetoshared.getOrDefault(pid, null);
			if ((shared != null) && shared.size() > 1) return false;
			else if ((shared == null) || shared.size() == 0) {
				this.pagetoexclusive.put(pid, tid);
				if(!this.transactiontopage.containsKey(tid)) this.transactiontopage.put(tid, new ArrayList<PageId>());
				ArrayList<PageId> pages = this.transactiontopage.get(tid);
				if (!pages.contains(pid)) pages.add(pid);
				
				return true;
			}
			else {
				if (shared.getOrDefault(tid, false)) {
					this.pagetoexclusive.put(pid, tid);
					if(!this.transactiontopage.containsKey(tid)) this.transactiontopage.put(tid, new ArrayList<PageId>());
					ArrayList<PageId> pages = this.transactiontopage.get(tid);
					if (!pages.contains(pid)) pages.add(pid);
					return true;
				}
				return false;
			}
		}
		
		private void release(TransactionId tid, PageId pid) {
			ConcurrentHashMap<TransactionId,Boolean> shared = this.pagetoshared.getOrDefault(pid, null);
			if (shared != null) {
				shared.remove(tid);
			}
			
			if (this.pagetoexclusive.containsKey(pid) && (this.pagetoexclusive.get(pid) == tid)) {
				this.pagetoexclusive.remove(pid);
			}
			ArrayList<PageId> pages = this.transactiontopage.getOrDefault(tid,null);
			if ((pages != null) && pages.contains(pid)) pages.remove(pages.indexOf(pid));
			
			
			return;
		}
		
		private boolean holdsLock(TransactionId tid, PageId pid) {
			boolean temp = false;
			ConcurrentHashMap<TransactionId,Boolean> shared = this.pagetoshared.getOrDefault(pid, null);
			if ((shared != null) && shared.contains(tid)) return true;
			if (this.pagetoexclusive.contains(pid) && (this.pagetoexclusive.get(pid) == tid)) return true;
			return false;
		}
		
		private ArrayList<PageId> getPages(TransactionId tid){
			return this.transactiontopage.getOrDefault(tid, null);
		}
		
		
	}
	
	private LockManager lockManager;
    /** Bytes per page, including header. */
    private static final int DEFAULT_PAGE_SIZE = 4096;

    private static int pageSize = DEFAULT_PAGE_SIZE;
    
    /** Default number of pages passed to the constructor. This is used by
    other classes. BufferPool should use the numPages argument to the
    constructor instead. */
    public static final int DEFAULT_PAGES = 50;

    private final Random random = new Random();
    final int numPages;   // number of pages -- currently, not enforced
    final ConcurrentMap<PageId, Page> pages; // hash table storing current pages in memory

    /**
     * Creates a BufferPool that caches up to numPages pages.
     *
     * @param numPages maximum number of pages in this buffer pool.
     */
    public BufferPool(int numPages) {
        this.numPages = numPages;
        this.pages = new ConcurrentHashMap<>();
        this.lockManager = new LockManager();
    }
    
    public static int getPageSize() {
      return pageSize;
    }
    
    // THIS FUNCTION SHOULD ONLY BE USED FOR TESTING!!
    public static void setPageSize(int pageSize) {
    	BufferPool.pageSize = pageSize;
    }
    
    // THIS FUNCTION SHOULD ONLY BE USED FOR TESTING!!
    public static void resetPageSize() {
    	BufferPool.pageSize = DEFAULT_PAGE_SIZE;
    }

    /**
     * Retrieve the specified page with the associated permissions.
     * Will acquire a lock and may block if that lock is held by another
     * transaction.
     * <p>
     * The retrieved page should be looked up in the buffer pool.  If it
     * is present, it should be returned.  If it is not present, it should
     * be added to the buffer pool and returned.  If there is insufficient
     * space in the buffer pool, a page should be evicted and the new page
     * should be added in its place.
     *
     * @param tid the ID of the transaction requesting the page
     * @param pid the ID of the requested page
     * @param perm the requested permissions on the page
     */
    public  Page getPage(TransactionId tid, PageId pid, Permissions perm)
        throws TransactionAbortedException, DbException {
        // XXX Yuan points out that HashMap is not synchronized, so this is buggy.
        // XXX TODO(ghuo): do we really know enough to implement NO STEAL here?
        //     won't we still evict pages?
        Page p;
        
        synchronized(this) {
        	boolean had_to_wait = false;
        	if(perm == Permissions.READ_ONLY) {
            	while (!this.lockManager.getShared(tid,pid)) {
            		try {
            			had_to_wait= true;
            			this.lockManager.insertGraph(tid,pid, perm);
						this.wait();
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					} catch (TransactionAbortedException e) {
						throw e;
					}
            	}
            }
            else if (perm == Permissions.READ_WRITE) {
            	while(!this.lockManager.getExclusive(tid,pid)) {
            		try {
            			had_to_wait = true;
            			this.lockManager.insertGraph(tid, pid, perm);
						this.wait();
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					} catch (TransactionAbortedException e) {
						throw e;
					}
            	}
            }
        	if (had_to_wait) this.lockManager.deleteGraph(tid, pid);
        	
        	p = pages.get(pid); 
            if(p == null) {
                if(pages.size() >= numPages) {
                    evictPage();
                }
                
                p = Database.getCatalog().getDatabaseFile(pid.getTableId()).readPage(pid);
                pages.put(pid, p);
            }
        }
        
        return p;
    }

    /**
     * Releases the lock on a page.
     * Calling this is very risky, and may result in wrong behavior. Think hard
     * about who needs to call this and why, and why they can run the risk of
     * calling it.
     *
     * @param tid the ID of the transaction requesting the unlock
     * @param pid the ID of the page to unlock
     */
    public  void unsafeReleasePage(TransactionId tid, PageId pid) {
        // some code goes here
    	this.lockManager.release(tid,pid);
    }

    /**
     * Release all locks associated with a given transaction.
     *
     * @param tid the ID of the transaction requesting the unlock
     */
    public void transactionComplete(TransactionId tid) {
        // some code goes here
    	
    	try {
			this.transactionComplete(tid, true);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    }

    /** Return true if the specified transaction has a lock on the specified page */
    public boolean holdsLock(TransactionId tid, PageId p) {
        // some code goes here
        // not necessary for lab1|lab2
        return this.lockManager.holdsLock(tid,p);
    }

    /**
     * Commit or abort a given transaction; release all locks associated to
     * the transaction.
     *
     * @param tid the ID of the transaction requesting the unlock
     * @param commit a flag indicating whether we should commit or abort
     */
    public void transactionComplete(TransactionId tid, boolean commit) {
        // some code goes here
    	ArrayList<PageId> pages = (ArrayList<PageId>) this.lockManager.getPages(tid).clone();
    	
    	if (commit) {
    		try {
				this.flushPages(tid);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
    	}
    	else {
    		for(PageId page : pages) {
    			this.discardPage(page);
    		}
    	}
    	for(PageId page : pages) {
    		this.lockManager.release(tid, page);
    	}
    }

    /**
     * Add a tuple to the specified table on behalf of transaction tid.  Will
     * acquire a write lock on the page the tuple is added to and any other 
     * pages that are updated (Lock acquisition is not needed for lab2). 
     * May block if the lock(s) cannot be acquired.
     * 
     * Marks any pages that were dirtied by the operation as dirty by calling
     * their markDirty bit, and adds versions of any pages that have 
     * been dirtied to the cache (replacing any existing versions of those pages) so 
     * that future requests see up-to-date pages. 
     *
     * @param tid the transaction adding the tuple
     * @param tableId the table to add the tuple to
     * @param t the tuple to add
     */
    public void insertTuple(TransactionId tid, int tableId, Tuple t)
        throws DbException, IOException, TransactionAbortedException {
        DbFile file = Database.getCatalog().getDatabaseFile(tableId);

        // let the specific implementation of the file decide which page to add it
        // to.

        List<Page> dirtypages = file.insertTuple(tid, t);

        synchronized(this) {
            for (Page p : dirtypages){
                p.markDirty(true, tid);
                
                //System.out.println("ADDING TUPLE TO PAGE " + p.getId().pageno() + " WITH HASH CODE " + p.getId().hashCode());
                
                // if page in pool already, done.
                if(pages.get(p.getId()) != null) {
                    //replace old page with new one in case addTuple returns a new copy of the page
                    pages.put(p.getId(), p);
                }
                else {
                    
                    // put page in pool
                    if(pages.size() >= numPages)
                        evictPage();
                    pages.put(p.getId(), p);
                }
            }
        }
    }

    /**
     * Remove the specified tuple from the buffer pool.
     * Will acquire a write lock on the page the tuple is removed from and any
     * other pages that are updated. May block if the lock(s) cannot be acquired.
     *
     * Marks any pages that were dirtied by the operation as dirty by calling
     * their markDirty bit, and adds versions of any pages that have 
     * been dirtied to the cache (replacing any existing versions of those pages) so 
     * that future requests see up-to-date pages. 
     *
     * @param tid the transaction deleting the tuple.
     * @param t the tuple to delete
     */
    public  void deleteTuple(TransactionId tid, Tuple t)
        throws DbException, IOException, TransactionAbortedException {
        DbFile file = Database.getCatalog().getDatabaseFile(t.getRecordId().getPageId().getTableId());
        List<Page> dirtypages = file.deleteTuple(tid, t);

        synchronized(this) {
        	for (Page p : dirtypages){
        		p.markDirty(true, tid);
                    
        		// if page in pool already, done.
        		if(pages.get(p.getId()) != null) {
        			//replace old page with new one in case deleteTuple returns a new copy of the page
        			pages.put(p.getId(), p);
                }
        		else {
                        
        			// put page in pool
        			if(pages.size() >= numPages)
        				evictPage();
                    pages.put(p.getId(), p);
                }	
        	}   
        }    
    }

    /**
     * Flush all dirty pages to disk.
     * NB: Be careful using this routine -- it writes dirty data to disk so will
     *     break simpledb if running in NO STEAL mode.
     */
    public synchronized void flushAllPages() throws IOException {
        /* calls flushPage() for each page in the BufferPool */
        for (PageId pageId : pages.keySet()) flushPage(pageId);

    }

    /** Remove the specific page id from the buffer pool.
        Needed by the recovery manager to ensure that the
        buffer pool doesn't keep a rolled back page in its
        cache.
        
        Also used by B+ tree files to ensure that deleted pages
        are removed from the cache so they can be reused safely
    */
    public synchronized void discardPage(PageId pid) {
        Page p = pages.get(pid);
        if (p != null) {
            pages.remove(pid);
        }
    }

    /**
     * Flushes a certain page to disk
     * @param pid an ID indicating the page to flush
     */
    private synchronized  void flushPage(PageId pid) throws IOException {
        
        Page p = pages.get(pid);
        if (p == null)
            return; //not in buffer pool -- doesn't need to be flushed

        DbFile file = Database.getCatalog().getDatabaseFile(pid.getTableId());
        file.writePage(p);
        p.markDirty(false, null);
    }

    /** Write all pages of the specified transaction to disk.
     */
    public synchronized  void flushPages(TransactionId tid) throws IOException {
        ArrayList<PageId> pages = (ArrayList<PageId>) this.lockManager.getPages(tid).clone();
        for (PageId page : pages) {
        	this.flushPage(page);
        }
    }

    /**
     * Discards a page from the buffer pool.
     * Flushes the page to disk to ensure dirty pages are updated on disk.
     */
    private synchronized  void evictPage() throws DbException {
        // pick a random page and flush it.
        // XXX this will work for lab1, but not for lab4.
        // XXX this can cause pages to be evicted that have uncommitted updates on them
        Object[] pids = pages.keySet().toArray();
        PageId pid = (PageId) pids[random.nextInt(pids.length)];
        try {
            Page p = pages.get(pid);
            if (p.isDirty() != null) { //if this is dirty, remove first non-dirty
                boolean gotNew = false;
                for (PageId pg : pages.keySet()) {
                    if (pages.get(pg).isDirty() == null) {
                        pid = pg;
                        gotNew = true;
                        break;
                    }
                }
                if (!gotNew) {
                    throw new DbException("All buffer pool slots contain dirty pages;  COMMIT or ROLLBACK to continue.");
                }
            }
            //XXX: The above code makes sure page is not dirty. 
            //Assuming we have FORCE, Why do we flush it to disk?
            //Answer: yes we don't need this if we have FORCE, but we do need it if we don't.
            //it doesn't hurt to keep it here.            
            flushPage(pid);
        } catch (IOException e) {
            throw new DbException("could not evict page");
        }
        pages.remove(pid);
    }

}
